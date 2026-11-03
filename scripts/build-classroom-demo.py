#!/usr/bin/env python3
"""Package built backend + frontend as an isolated, offline classroom app. No runtime data included."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/classroom-demo')
    args = parser.parse_args()
    jar = ROOT/'backend/target/lianzhi-backend-0.1.0.jar'
    dist = ROOT/'frontend/dist'
    if not jar.is_file() or not (dist/'index.html').is_file():
        raise RuntimeError('Build backend (mvnw verify) and frontend (npm run build) first.')
    for source, artifact in [(ROOT/'backend/src/main', jar), (ROOT/'frontend/src', dist/'index.html')]:
        if any(p.is_file() and p.stat().st_mtime > artifact.stat().st_mtime for p in source.rglob('*')):
            raise RuntimeError('Source is newer than the build. Rebuild before packaging: '+str(source))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as source, zipfile.ZipFile(output/'app.jar', 'w') as target:
        if not any('ClassroomConfiguration.class' in name for name in source.namelist()):
            raise RuntimeError('Backend was built before classroom support. Rebuild it.')
        for entry in source.infolist():
            # Preserve compression modes, including STORED nested Spring Boot dependency JARs.
            if not entry.filename.startswith('BOOT-INF/classes/static/'):
                target.writestr(entry, source.read(entry.filename))
        for asset in dist.rglob('*'):
            if asset.is_file(): target.write(asset, 'BOOT-INF/classes/static/'+asset.relative_to(dist).as_posix())
    for filename in ['start-demo.cmd','start-demo.ps1','start-demo.sh']:
        shutil.copy2(ROOT/'tools/classroom'/filename, output/filename)
    (output/'start-demo.sh').chmod(0o755)
    (output/'README.txt').write_text('''联智课堂展示包（合成测试数据，不作真实员工考勤）

Windows：完整解压到普通目录，双击 start-demo.cmd，保留窗口。
Linux/macOS：在目录运行 sh start-demo.sh。
需要 Java 17 或以上，可通过 JAVA_HOME 指定；运行时不需要 Node、MySQL、Python、网络或人脸SDK。
首次启动在空的demo-data目录创建自己的演示库，不读取原项目数据库。

浏览器地址：http://127.0.0.1:18080/login?redirect=/attendance/presentation
点击“管理员”后登录，依次执行演示台步骤1—5。
公开演示账号：admin / hr / employee，公开密码 Lianzhi@2026。
展示台会创建独立的合成员工并自动切换身份；二维码实际签发及图片解码，定位明确使用演示坐标。
打卡和审批调用真实服务，成功结果来自数据库，不预写出勤。快速上下班可能显示0分钟及早退，属于实际规则计算。
人脸仅支持摄像头预览和状态展示，实际SDK未接入，不宣称识别/活体通过。

不要公开此演示入口或录入真实员工资料。地址仅限本机，手机HTTPS沿用项目独立验收工具。
占用端口18080时先关闭本次旧演示窗口；程序不停止已有其他服务。
退出后数据保留在demo-data，下一轮点击“重新准备新一轮展示”即可，不删除旧记录。
展示前先在实际Windows机器运行一遍，确认Java与摄像头权限。Windows启动脚本未在云端实际执行。
''', encoding='utf-8-sig')
    commit = subprocess.check_output(['git','rev-parse','HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip())
    (output/'manifest.json').write_text(json.dumps({'commit':commit,'working_tree_dirty':dirty,'mode':'classroom','real_face_sdk':False},indent=2))
    archive = output.parent/(output.name+'.zip')
    # Explicit allowlist: never include demo-data, logs, uploaded pictures, credentials or prior files.
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as package:
        for filename in ['app.jar','start-demo.cmd','start-demo.ps1','start-demo.sh','README.txt','manifest.json']:
            package.write(output/filename,output.name+'/'+filename)
    print(json.dumps({'directory':str(output),'archive':str(archive),'bytes':archive.stat().st_size},ensure_ascii=False))

if __name__ == '__main__':
    main()
