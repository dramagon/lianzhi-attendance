package cn.lianzhi.attendance.knowledge.provider;

import cn.lianzhi.attendance.knowledge.text.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Configured service contract: JSON upload -> measured source sections; no redirects or retries. */
public final class MediaTextClient {
    private final URI endpoint;private final String token;private final boolean timed;private final ObjectMapper json=new ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MediaTextClient(String url,String token,boolean timed){this.timed=timed;this.token=token==null?"":token;if(this.token.length()>4096||this.token.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("媒体服务凭据格式无效");
        if(url==null||url.isBlank()){endpoint=null;return;}URI uri;try{uri=URI.create(url.strip());}catch(RuntimeException e){throw new IllegalArgumentException("媒体服务地址无效");}
        if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),""))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("媒体服务地址须为不含凭据和查询的HTTP(S)端点");endpoint=uri;
    }
    public ProviderAvailability availability(){return endpoint==null?ProviderAvailability.NOT_CONFIGURED:ProviderAvailability.AVAILABLE;}
    public ProviderResult<List<TextSection>> extract(String name,byte[] bytes){
        if(endpoint==null)return new ProviderResult<>(ProviderResult.State.UNAVAILABLE,"媒体文字服务未配置",null);
        if(name==null||name.length()>200||bytes==null||bytes.length==0||bytes.length>10*1024*1024)return fail();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            var body=json.writeValueAsBytes(Map.of("fileName",name,"base64",Base64.getEncoder().encodeToString(bytes)));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");if(!token.isBlank())request.header("Authorization","Bearer "+token);
            pending=client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),ignored->new LimitedBody());var response=pending.get(60,TimeUnit.SECONDS);if(response.statusCode()!=200)return fail();
            var root=json.readTree(response.body());var sections=root.path("sections");if(!sections.isArray()||sections.size()>1000)return fail();var result=new ArrayList<TextSection>();int count=0;long last=0;
            for(var node:sections){var s=json.treeToValue(node,TextSection.class);if(s.text().length()>160_000)return fail();count+=s.text().codePointCount(0,s.text().length());if(count>160_000)return fail();
                if(timed){if(s.source().kind()!=SourceLocation.Kind.TIME_RANGE||s.source().startMillis()<last||s.source().endMillis()>24*60*60*1000L)return fail();last=s.source().endMillis();}
                else if(s.source().kind()!=SourceLocation.Kind.PAGE && s.source().kind()!=SourceLocation.Kind.FILE)return fail();
                result.add(new TextSection(s.source(),TextNormalizer.normalize(s.text())));
            }
            return new ProviderResult<>(ProviderResult.State.SUCCESS,"媒体文字提取完成",List.copyOf(result));
        }catch(InterruptedException e){Thread.currentThread().interrupt();return fail();}catch(Exception e){return fail();}finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private static ProviderResult<List<TextSection>> fail(){return new ProviderResult<>(ProviderResult.State.FAILURE,"媒体文字服务失败、超时或返回无效来源",null);}
    public static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]>{
        final CompletableFuture<byte[]> done=new CompletableFuture<>();final ByteArrayOutputStream out=new ByteArrayOutputStream();java.util.concurrent.Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return done;}public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers){for(var b:buffers){if(b.remaining()>2*1024*1024-out.size()){subscription.cancel();done.completeExceptionally(new IOException("limit"));return;}byte[] bytes=new byte[b.remaining()];b.get(bytes);out.writeBytes(bytes);}subscription.request(1);}
        public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(out.toByteArray());}
    }
}
