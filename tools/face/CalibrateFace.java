import cn.lianzhi.attendance.face.ArcSoftFaceProvider;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executors;

/** Explicitly launched loopback-only capture trials. No photos, features or credentials are written. */
public class CalibrateFace {
    private static byte[] reference;
    private static ArcSoftFaceProvider provider;
    private static HttpServer server;
    private static String token;
    private static final String ORIGIN="http://127.0.0.1:18081";
    public static void main(String[] args) throws Exception {
        provider=new ArcSoftFaceProvider(System.getenv("LIANZHI_ARCSOFT_JAR"),System.getenv("LIANZHI_ARCSOFT_NATIVE_DIRECTORY"),
                System.getenv("LIANZHI_ARCSOFT_APP_ID"),System.getenv("LIANZHI_ARCSOFT_SDK_KEY"),.8);
        if (!provider.capabilities().detection()) throw new IllegalStateException(provider.capabilities().message());
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",18081),0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/",CalibrateFace::request);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{wipe();provider.close();server.stop(0);}));
        server.start();
        System.out.println("FACE_TRIAL_READY "+ORIGIN+" | Camera samples stay in memory. Ctrl+C stops this tool.");
    }
    private static void request(HttpExchange exchange) throws java.io.IOException {
        try {
            if(!"127.0.0.1:18081".equals(exchange.getRequestHeaders().getFirst("Host"))) { send(exchange,403,"拒绝访问","text/plain");return; }
            String path=exchange.getRequestURI().getPath();
            if("GET".equals(exchange.getRequestMethod()) && path.equals("/")) { send(exchange,200,PAGE.replace("__TOKEN__",token),"text/html");return; }
            if(!"POST".equals(exchange.getRequestMethod()) || !(path.equals("/sample") || path.equals("/reset"))) { send(exchange,404,"{}","application/json");return; }
            if(!ORIGIN.equals(exchange.getRequestHeaders().getFirst("Origin")) || !token.equals(exchange.getRequestHeaders().getFirst("X-Capture-Token"))) {
                send(exchange,403,"{\"message\":\"本机采集校验失败，请刷新页面\"}","application/json");return;
            }
            if(path.equals("/reset")) { wipe();send(exchange,200,"{\"message\":\"已清除内存中的参照特征\"}","application/json");return; }
            if(!"true".equals(exchange.getRequestHeaders().getFirst("X-Capture-Consent"))) {
                send(exchange,400,"{\"message\":\"请先确认采集者同意\"}","application/json");return;
            }
            byte[] image=exchange.getRequestBody().readNBytes(2*1024*1024+1);
            byte[] feature=null;
            try {
                if(image.length>2*1024*1024) { send(exchange,413,"{\"message\":\"图像超过2MB\"}","application/json");return; }
                var sample=provider.analyze(image);feature=sample.feature();
                if(sample.faces()!=1 || !sample.qualityAccepted() || !sample.live() || feature==null) {
                    send(exchange,200,"{\"accepted\":false,\"faces\":"+sample.faces()+",\"quality\":"+sample.qualityAccepted()+",\"live\":"+sample.live()+",\"message\":\"未通过单人、质量或RGB活体检查，请正对摄像头并改善光线\"}","application/json");return;
                }
                if(reference==null) {
                    reference=feature.clone();send(exchange,200,"{\"accepted\":true,\"reference\":true,\"message\":\"参照采集成功。请继续采集本人样本，再由另一位同意的测试者采集非本人样本\"}","application/json");return;
                }
                double score=provider.compare(reference,feature);
                send(exchange,200,"{\"accepted\":true,\"score\":"+score+",\"message\":\"已取得真实比对分数；本工具不据此自动判定身份或修改阈值\"}","application/json");
            } finally { Arrays.fill(image,(byte)0);if(feature!=null)Arrays.fill(feature,(byte)0); }
        } catch(Exception | LinkageError failure) {
            send(exchange,503,"{\"message\":\"SDK处理失败，请检查本机服务；未保存采集图像\"}","application/json");
        } finally { exchange.close(); }
    }
    private static void send(HttpExchange exchange,int code,String value,String type) throws java.io.IOException {
        byte[] content=value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(code,content.length);exchange.getResponseBody().write(content);
    }
    private static void wipe() { if(reference!=null)Arrays.fill(reference,(byte)0);reference=null; }
    private static final String PAGE="""
        <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>人脸现场测试</title><style>body{font:16px system-ui;max-width:860px;margin:32px auto;padding:0 20px;color:#18334a;background:#f4f7fb}video{width:100%;max-height:420px;background:#132536;border-radius:12px}button{font:inherit;padding:10px 18px;margin:12px 8px 8px 0;cursor:pointer}li{margin:8px 0}#result{white-space:pre-wrap;background:white;padding:18px;border-radius:10px}table{width:100%;border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #ccd6e0;text-align:left}</style>
        <h1>人脸现场测试</h1><p>采集只用于当前电脑上的检测、RGB活体和比对分数测试，不登记到员工档案。图像不保存，参照特征仅在工具内存中暂存。</p>
        <ol><li>本人正对摄像头，先采集一张参照。</li><li>连续采集至少三次本人样本，适当改变距离和光线。</li><li>切换“非本人样本”，由另一位同意测试的人采集至少三次。测试照片和屏幕时也应标为非本人。</li><li>把分数结果提供给开发人员核对；样本过少不能证明正式环境准确率。</li></ol>
        <label><input id="consent" type="checkbox">当前采集者同意本次图像处理与内存特征比对</label><br>
        <button id="open">开启摄像头</button><button id="stop" disabled>停止摄像头</button>
        <video id="video" autoplay muted playsinline aria-label="现场采集画面"></video>
        <label>样本类型 <select id="kind"><option value="本人">本人样本</option><option value="非本人">非本人样本</option></select></label><br>
        <button id="capture" disabled>采集参照 / 测试样本</button><button id="reset">清除参照与结果</button>
        <p id="result" role="status">尚未采集。请先同意并开启摄像头。</p><table><thead><tr><th>样本</th><th>比对分数</th></tr></thead><tbody id="scores"></tbody></table>
        <script>
        const token='__TOKEN__',video=document.getElementById('video'),consent=document.getElementById('consent'),capture=document.getElementById('capture'),open=document.getElementById('open'),stopButton=document.getElementById('stop'),result=document.getElementById('result');
        let stream,revision=0,busy=false,rows=[];
        function stop(){revision++;stream?.getTracks().forEach(t=>t.stop());stream=null;video.srcObject=null;capture.disabled=true;stopButton.disabled=true;open.disabled=false;}
        async function request(path,body){const r=await fetch(path,{method:'POST',headers:{'X-Capture-Token':token,'X-Capture-Consent':String(consent.checked)},body});const data=await r.json();if(!r.ok)throw Error(data.message);return data;}
        open.onclick=async()=>{if(!consent.checked){result.textContent='请先确认采集者同意';return;}stop();const current=revision;open.disabled=true;try{const media=await navigator.mediaDevices.getUserMedia({video:{facingMode:'user'},audio:false});if(current!==revision||!consent.checked){media.getTracks().forEach(t=>t.stop());return;}stream=media;video.srcObject=media;await video.play();capture.disabled=false;stopButton.disabled=false;result.textContent='画面就绪，请正对摄像头采集';}catch(e){stop();result.textContent='摄像头不可用，请检查授权或设备占用';}};
        stopButton.onclick=stop;
        capture.onclick=async()=>{if(busy||!stream||!consent.checked)return;const current=revision;const kind=document.getElementById('kind').value;busy=true;capture.disabled=true;const canvas=document.createElement('canvas');try{const scale=Math.min(1,1280/Math.max(video.videoWidth,video.videoHeight));canvas.width=Math.floor(video.videoWidth*scale);canvas.height=Math.floor(video.videoHeight*scale);canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);const image=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',.9));if(current!==revision||!consent.checked)return;const data=await request('/sample',image);if(current!==revision)return;result.textContent=data.message+(data.faces!==undefined?'；人数 '+data.faces+'，质量 '+data.quality+'，RGB活体 '+data.live:'');if(typeof data.score==='number'){rows.push({kind,score:data.score});const row=document.createElement('tr');for(const text of [kind,data.score.toFixed(6)]){const cell=document.createElement('td');cell.textContent=text;row.appendChild(cell);}document.getElementById('scores').appendChild(row);const own=rows.filter(r=>r.kind==='本人'),other=rows.filter(r=>r.kind==='非本人');if(own.length>=3&&other.length>=3){const low=Math.min(...own.map(r=>r.score)),high=Math.max(...other.map(r=>r.score));result.textContent+='；本人最低 '+low.toFixed(6)+'，非本人最高 '+high.toFixed(6)+(low>high?'；本轮分数有间隔，仍需人工评估阈值':'；分数重叠，不能据此启用身份判定');}}}catch(e){result.textContent=e.message||'采集失败';}finally{canvas.width=canvas.height=0;busy=false;capture.disabled=!stream||!consent.checked;}};
        async function reset(){stop();await request('/reset');rows=[];document.getElementById('scores').replaceChildren();result.textContent='已清除参照与结果';}
        document.getElementById('reset').onclick=()=>reset().catch(()=>result.textContent='清除失败，请关闭本机测试工具');
        consent.onchange=()=>{if(!consent.checked)reset().catch(()=>result.textContent='请关闭测试工具以清除内存参照');};
        document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
        window.addEventListener('pagehide',()=>{stop();fetch('/reset',{method:'POST',keepalive:true,headers:{'X-Capture-Token':token}}).catch(()=>{});});
        </script></html>
        """;
}
