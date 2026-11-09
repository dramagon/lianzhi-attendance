package cn.lianzhi.attendance.knowledge.storage;

import cn.lianzhi.attendance.knowledge.parse.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Private child-process protocol. Never accepts an HTTP path or runs document instructions. */
public final class IsolatedParserMain {
    public static void main(String[] args)throws Exception{
        if(args.length!=3)System.exit(2);
        var json=new ObjectMapper();String name=json.readValue(Files.readAllBytes(Path.of(args[0])),String.class);
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));
        var result=new DocumentParser(new ParseOptions(10*1024*1024,160_000,1000,1000,200_000,2000,16*1024*1024,64*1024*1024)).parse(name,bytes);
        Files.write(Path.of(args[2]),json.writeValueAsBytes(result));
    }
}
