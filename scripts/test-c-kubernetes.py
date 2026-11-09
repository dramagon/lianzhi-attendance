"""Exercise the deployment on a newly created, disposable kind cluster."""
import argparse
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time

from c_http_verification import Client, after_restart, verify

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "deploy/kubernetes/app.yaml"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cluster", required=True)
    args = parser.parse_args()
    context = "kind-" + args.cluster
    output = ROOT / "artifacts/c-kubernetes"
    output.mkdir(parents=True, exist_ok=True)

    def run(*command, data=None, timeout=360):
        subprocess.run(command, cwd=ROOT, input=data, check=True, timeout=timeout)

    def kubectl(*command, **options):
        run("kubectl", "--context", context, "-n", "lianzhi", *command, **options)

    def create(document):
        kubectl("create", "-f", "-", data=json.dumps(document).encode())

    run("docker", "build", "-f", "deploy/backend.Dockerfile", "-t", "lianzhi-backend:local", ".", timeout=900)
    run("docker", "build", "-f", "deploy/frontend.Dockerfile", "-t", "lianzhi-web:local", ".", timeout=900)
    run("kind", "load", "docker-image", "--name", args.cluster, "lianzhi-backend:local", "lianzhi-web:local")
    kubectl("create", "namespace", "lianzhi")

    db_password = secrets.token_hex(24)
    admin_password = secrets.token_hex(24)
    create({
        "apiVersion": "v1", "kind": "Secret", "metadata": {"name": "lianzhi-runtime"},
        "stringData": {
            "DB_URL": "jdbc:mysql://mysql:3306/lianzhi?serverTimezone=Asia/Shanghai",
            "DB_USERNAME": "lianzhi", "DB_PASSWORD": db_password,
            "ADMIN_INITIAL_PASSWORD": admin_password,
        },
    })
    create({
        "apiVersion": "v1", "kind": "Secret", "metadata": {"name": "test-mysql"},
        "stringData": {"MYSQL_ROOT_PASSWORD": secrets.token_hex(24), "MYSQL_PASSWORD": db_password},
    })
    create({
        "apiVersion": "v1", "kind": "Service", "metadata": {"name": "mysql"},
        "spec": {"selector": {"app": "mysql"}, "ports": [{"port": 3306}]},
    })
    create({
        "apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "mysql"},
        "spec": {
            "selector": {"matchLabels": {"app": "mysql"}},
            "template": {
                "metadata": {"labels": {"app": "mysql"}},
                "spec": {"containers": [{
                    "name": "mysql", "image": "mysql:8.4",
                    "envFrom": [{"secretRef": {"name": "test-mysql"}}],
                    "env": [{"name": "MYSQL_DATABASE", "value": "lianzhi"},
                            {"name": "MYSQL_USER", "value": "lianzhi"}],
                    "readinessProbe": {"exec": {"command": [
                        "sh", "-ec", 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u lianzhi lianzhi -e "SELECT 1" >/dev/null',
                    ]}, "periodSeconds": 5},
                }]},
            },
        },
    })
    kubectl("rollout", "status", "deployment/mysql", "--timeout=300s")
    kubectl("apply", "--dry-run=server", "-f", str(MANIFEST))
    kubectl("apply", "-f", str(MANIFEST))
    for component in ("backend", "web"):
        kubectl("rollout", "status", "deployment/" + component, "--timeout=300s")

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    with (output / "port-forward.log").open("w", encoding="utf-8") as log:
        with subprocess.Popen(
            ["kubectl", "--context", context, "-n", "lianzhi", "port-forward",
             "--address", "127.0.0.1", "service/web", f"{port}:80"],
            stdout=log, stderr=subprocess.STDOUT,
        ) as forward:
            try:
                for _ in range(30):
                    if forward.poll() is not None:
                        raise RuntimeError("Web port-forward stopped")
                    try:
                        Client(origin).get("/api/system/info")
                        break
                    except Exception:
                        time.sleep(1)
                else:
                    raise RuntimeError("Web did not become reachable")
                proof = verify(Client(origin), admin_password)
                kubectl("exec", "deployment/backend", "--", "touch", "/app/data/avatars/.k8s-smoke")
                kubectl("rollout", "restart", "deployment/backend")
                kubectl("rollout", "status", "deployment/backend", "--timeout=300s")
                kubectl("exec", "deployment/backend", "--", "test", "-f", "/app/data/avatars/.k8s-smoke")
                after_restart(Client(origin), admin_password, proof)
            finally:
                forward.terminate()
    result = {"kind": True, "serverDryRun": True, "sameOrigin": True, "mysqlV6": True,
              "sourceAndCsv": True, "backendRestart": True, "avatarPvcRetained": True}
    (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
