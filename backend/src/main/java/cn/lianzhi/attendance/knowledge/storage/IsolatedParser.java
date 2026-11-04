package cn.lianzhi.attendance.knowledge.storage;

import cn.lianzhi.attendance.knowledge.parse.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class IsolatedParser {
    private final ObjectMapper json;
    public IsolatedParser(ObjectMapper json){this.json=json;}
    public ParseResult parse(String name,byte[] bytes){
        Path dir=null;Process process=null;
        try{
            dir=Files.createTempDirectory("lianzhi-parse-");Path meta=dir.resolve("name.json"),input=dir.resolve("input.bin"),output=dir.resolve("result.json");
            Files.write(meta,json.writeValueAsBytes(name));Files.write(input,bytes);
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var command=new ArrayList<String>(List.of(java,"-Xmx256m","-XX:ActiveProcessorCount=1"));
            String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            if(System.getProperty("surefire.test.class.path")==null && System.getProperty("sun.java.command","").contains(".jar"))command.addAll(List.of("-Dloader.main="+IsolatedParserMain.class.getName(),"-cp",classpath,"org.springframework.boot.loader.launch.PropertiesLauncher"));
            else command.addAll(List.of("-cp",classpath,IsolatedParserMain.class.getName()));
            command.addAll(List.of(meta.toString(),input.toString(),output.toString()));
            process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(45,TimeUnit.SECONDS)||process.exitValue()!=0||!Files.isRegularFile(output)||Files.size(output)>2*1024*1024)throw new IllegalStateException();
            return json.readValue(Files.readAllBytes(output),ParseResult.class);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new DocumentContentProcessor.ProcessingException("FAILED","解析被中断");}
        catch(Exception e){throw new DocumentContentProcessor.ProcessingException("FAILED","隔离解析失败、超时或资源超限");}
        finally{
            if(process!=null && process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(dir!=null)try{for(String n:List.of("name.json","input.bin","result.json"))Files.deleteIfExists(dir.resolve(n));Files.deleteIfExists(dir);}catch(java.io.IOException ignored){}
        }
    }
}
