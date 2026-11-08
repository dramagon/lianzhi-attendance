package cn.lianzhi.attendance.face;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Optional, locally licensed ArcFace 3.0 adapter. Vendor binaries never enter the application JAR. */
@Component
@ConditionalOnProperty(name="lianzhi.face.arcsoft.enabled", havingValue="true")
public final class ArcSoftFaceProvider implements FaceProvider, AutoCloseable {
    private static final String PROVIDER="ARCSOFT_ARCFACE_WINDOWS_X64_V3";
    private URLClassLoader loader;
    private Object engine;
    private boolean initialized;
    private String version="UNAVAILABLE", message="虹软引擎尚未初始化";

    public ArcSoftFaceProvider(
            @Value("${lianzhi.face.arcsoft.jar:}") String jar,
            @Value("${lianzhi.face.arcsoft.native-directory:}") String nativeDirectory,
            @Value("${lianzhi.face.arcsoft.app-id:}") String appId,
            @Value("${lianzhi.face.arcsoft.sdk-key:}") String sdkKey,
            @Value("${lianzhi.face.arcsoft.rgb-liveness-threshold:0.8}") double livenessThreshold) {
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                    || !"amd64".equals(System.getProperty("os.arch"))) throw new IllegalStateException("仅支持Windows x64");
            if (appId.isBlank() || sdkKey.isBlank()) throw new IllegalStateException("未配置虹软授权");
            if (!Double.isFinite(livenessThreshold) || livenessThreshold<=0 || livenessThreshold>=1)
                throw new IllegalStateException("RGB活体阈值须位于0与1之间");
            Path sdk=Path.of(jar).toAbsolutePath(), libraries=Path.of(nativeDirectory).toAbsolutePath();
            if (jar.isBlank() || nativeDirectory.isBlank() || !Files.isRegularFile(sdk)
                    || !List.of("libarcsoft_face.dll", "libarcsoft_face_engine.dll", "libarcsoft_face_engine_jni.dll")
                    .stream().allMatch(name -> Files.isRegularFile(libraries.resolve(name))))
                throw new IllegalStateException("虹软Java库或DLL不完整");
            loader=new URLClassLoader(new java.net.URL[]{sdk.toUri().toURL()}, getClass().getClassLoader());
            engine=type("FaceEngine").getConstructor(String.class).newInstance(libraries.toString());
            int activation=number(invoke(engine,"activeOnline",appId,sdkKey));
            int already=number(invoke(type("enums.ErrorInfo").getField("MERR_ASF_ALREADY_ACTIVATED").get(null),"getValue"));
            if (activation!=0 && activation!=already) throw new IllegalStateException("虹软授权激活失败，错误码"+activation);
            Object configuration=create("EngineConfiguration");
            invoke(configuration,"setDetectMode",constant("DetectMode","ASF_DETECT_MODE_IMAGE"));
            invoke(configuration,"setDetectFaceOrientPriority",constant("DetectOrient","ASF_OP_ALL_OUT"));
            invoke(configuration,"setDetectFaceMaxNum",5);
            invoke(configuration,"setDetectFaceScaleVal",16);
            Object functions=functions();
            invoke(functions,"setSupportFaceDetect",true);
            invoke(functions,"setSupportFaceRecognition",true);
            invoke(configuration,"setFunctionConfiguration",functions);
            check(invoke(engine,"init",configuration),"引擎初始化");
            initialized=true;
            check(invoke(engine,"setLivenessParam",(float)livenessThreshold,0.7f),"RGB活体阈值设置");
            version=(String)invoke(invoke(engine,"getVersion"),"getVersion");
            if (version==null || !version.startsWith("3.0.")) throw new IllegalStateException("需要ArcFace 3.0引擎");
            message="虹软授权与引擎初始化成功；RGB活体、比对效果须另行现场验收";
        } catch (Exception | LinkageError failure) {
            // Only our bounded diagnostic is safe to expose; native/reflection messages may contain local secrets.
            message=failure instanceof IllegalStateException ? failure.getMessage() : "虹软引擎加载失败，请检查本机运行库与授权";
            close();
        }
    }

    @Override public synchronized Capabilities capabilities() {
        return new Capabilities(initialized,initialized,initialized,PROVIDER,message);
    }
    @Override public synchronized String featureVersion() { return version; }

    @Override public synchronized Sample analyze(byte[] encodedImage) {
        requireReady();
        byte[] pixels=null, extracted=null;
        try {
            // Bound decoding even when this adapter is called outside FaceService.
            validateImage(encodedImage);
            Object image=invoke(type("toolkit.ImageFactory"),"getRGBData",encodedImage);
            if (image==null) throw new IllegalArgumentException("图像无法解码");
            pixels=(byte[])invoke(image,"getImageData");
            int width=number(invoke(image,"getWidth")), height=number(invoke(image,"getHeight"));
            Object format=invoke(image,"getImageFormat");
            List<Object> faces=new ArrayList<>();
            check(invoke(engine,"detectFaces",pixels,width,height,format,faces),"人脸检测");
            if (faces.size()!=1) return new Sample(faces.size(),false,false,null);
            Object rect=invoke(faces.get(0),"getRect");
            int left=number(invoke(rect,"getLeft")), top=number(invoke(rect,"getTop"));
            int right=number(invoke(rect,"getRight")), bottom=number(invoke(rect,"getBottom"));
            if (!quality(pixels,width,height,left,top,right,bottom)) return new Sample(1,false,false,null);
            check(invoke(engine,"process",pixels,width,height,format,faces,functions()),"RGB活体与姿态处理");
            List<Object> live=new ArrayList<>(), angles=new ArrayList<>();
            check(invoke(engine,"getLiveness",live),"RGB活体结果读取");
            check(invoke(engine,"getFace3DAngle",angles),"姿态结果读取");
            boolean pose=angles.size()==1 && number(invoke(angles.get(0),"getStatus"))==0
                    && acceptableAngle(invoke(angles.get(0),"getYaw"))
                    && acceptableAngle(invoke(angles.get(0),"getPitch"))
                    && acceptableAngle(invoke(angles.get(0),"getRoll"));
            boolean isLive=live.size()==1 && number(invoke(live.get(0),"getLiveness"))==1;
            if (!pose || !isLive) return new Sample(1,pose,isLive,null);
            Object feature=create("FaceFeature");
            Object extraction=invoke(engine,"extractFaceFeature",pixels,width,height,format,faces.get(0),feature);
            extracted=(byte[])invoke(feature,"getFeatureData");
            int lowConfidence=number(invoke(type("enums.ErrorInfo").getField("MERR_FSDK_FACEFEATURE_LOW_CONFIDENCE_LEVEL").get(null),"getValue"));
            if (number(extraction)==lowConfidence) return new Sample(1,false,true,null);
            check(extraction,"特征提取");
            if (extracted==null || extracted.length==0 || extracted.length>32768) throw new IllegalStateException("虹软特征长度异常");
            return new Sample(1,true,true,extracted.clone());
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) {
            failClosed(); throw new IllegalStateException("虹软调用失败");
        } finally {
            if (pixels!=null) Arrays.fill(pixels,(byte)0);
            if (extracted!=null) Arrays.fill(extracted,(byte)0);
        }
    }

    @Override public synchronized double compare(byte[] enrolledFeature, byte[] candidateFeature) {
        requireReady();
        if (enrolledFeature==null || candidateFeature==null || enrolledFeature.length==0 || candidateFeature.length==0
                || enrolledFeature.length>32768 || candidateFeature.length>32768) throw new IllegalArgumentException("无效特征");
        byte[] first=enrolledFeature.clone(), second=candidateFeature.clone();
        try {
            Object a=create("FaceFeature"), b=create("FaceFeature"), score=create("FaceSimilar");
            invoke(a,"setFeatureData",first); invoke(b,"setFeatureData",second);
            check(invoke(engine,"compareFaceFeature",a,b,score),"特征比对");
            double result=((Number)invoke(score,"getScore")).doubleValue();
            if (!Double.isFinite(result) || result<0 || result>1) throw new IllegalStateException("虹软分数超出范围");
            return result;
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) {
            failClosed(); throw new IllegalStateException("虹软调用失败");
        } finally { Arrays.fill(first,(byte)0); Arrays.fill(second,(byte)0); }
    }

    private void requireReady() { if (!initialized) throw new IllegalStateException("虹软引擎不可用"); }
    private void failClosed() { close(); message="虹软运行库调用失败，已关闭采集，请检查后重启"; }
    private static boolean acceptableAngle(Object value) { double v=((Number)value).doubleValue(); return Double.isFinite(v) && Math.abs(v)<=20; }
    private static void validateImage(byte[] bytes) {
        if (bytes==null || bytes.length==0 || bytes.length>2*1024*1024) throw new IllegalArgumentException("图像大小无效");
        try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图像无法读取");
            var reader=readers.next();
            try {
                reader.setInput(input); int w=reader.getWidth(0), h=reader.getHeight(0);
                if (!List.of("JPEG","JPG","PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT))
                        || w<100 || h<100 || w>4096 || h>4096 || (long)w*h>8_000_000) throw new IllegalArgumentException("图像尺寸或格式无效");
            } finally { reader.dispose(); }
        } catch (java.io.IOException e) { throw new IllegalArgumentException("图像损坏"); }
    }
    // Application prechecks, not an SDK quality score or a claim of calibrated accuracy.
    static boolean quality(byte[] bgr,int width,int height,int left,int top,int right,int bottom) {
        if (bgr==null || bgr.length!=(long)width*height*3 || left<0 || top<0 || right>width || bottom>height
                || right-left<80 || bottom-top<80) return false;
        double sum=0,squares=0; int n=0;
        for (int y=top;y<bottom;y+=2) for (int x=left;x<right;x+=2) {
            int offset=(y*width+x)*3;
            double luminance=.114*(bgr[offset]&255)+.587*(bgr[offset+1]&255)+.299*(bgr[offset+2]&255);
            sum+=luminance; squares+=luminance*luminance; n++;
        }
        double mean=sum/n;
        return mean>=25 && mean<=230 && squares/n-mean*mean>=144;
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName("com.arcsoft.face."+name,true,loader); }
    private Object create(String name) throws ReflectiveOperationException { return type(name).getConstructor().newInstance(); }
    private Object constant(String name,String value) throws ReflectiveOperationException { return type("enums."+name).getField(value).get(null); }
    private Object functions() throws ReflectiveOperationException {
        Object functions=create("FunctionConfiguration");
        invoke(functions,"setSupportLiveness",true); invoke(functions,"setSupportFace3dAngle",true); return functions;
    }
    private static int number(Object value) { return ((Number)value).intValue(); }
    private static void check(Object value,String operation) { int code=number(value); if(code!=0) throw new IllegalStateException(operation+"失败，错误码"+code); }
    private static Object invoke(Object target,String name,Object... args) throws ReflectiveOperationException {
        Class<?> owner=target instanceof Class<?> c ? c : target.getClass();
        Method selected=null;
        for (Method method:owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount()!=args.length) continue;
            Class<?>[] parameters=method.getParameterTypes(); boolean matches=true;
            for (int i=0;i<args.length;i++) {
                Class<?> p=parameters[i];
                if (p==int.class) p=Integer.class; else if(p==float.class) p=Float.class; else if(p==boolean.class) p=Boolean.class;
                if (args[i]==null || !p.isInstance(args[i])) { matches=false; break; }
            }
            if (matches) { if(selected!=null) throw new NoSuchMethodException("Ambiguous SDK method"); selected=method; }
        }
        if (selected==null) throw new NoSuchMethodException("Unsupported SDK API");
        // Do not propagate target exception text, which may contain licensing information.
        try { return selected.invoke(target instanceof Class<?> ? null : target,args); }
        catch (InvocationTargetException failure) { throw new ReflectiveOperationException("SDK invocation failed"); }
    }
    @Override @PreDestroy public synchronized void close() {
        if (initialized) { try { invoke(engine,"unInit"); } catch (Exception | LinkageError ignored) {} }
        initialized=false; engine=null;
        if(loader!=null) { try { loader.close(); } catch(java.io.IOException ignored) {} loader=null; }
    }
}
