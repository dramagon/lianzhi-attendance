package cn.lianzhi.attendance.knowledge.storage;

import cn.lianzhi.attendance.knowledge.parse.*;
import cn.lianzhi.attendance.knowledge.provider.*;
import cn.lianzhi.attendance.knowledge.text.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DocumentContentProcessor {
    public static final class ProcessingException extends RuntimeException{private final String state;public ProcessingException(String state,String message){super(message);this.state=state;}public String state(){return state;}}
    private final IsolatedParser parser;private final OcrProvider ocr;private final TranscriptionProvider transcription;
    public DocumentContentProcessor(IsolatedParser parser,OcrProvider ocr,TranscriptionProvider transcription){this.parser=parser;this.ocr=ocr;this.transcription=transcription;}
    public List<TextSection> parse(String name,byte[] bytes){
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(Set.of("png","jpg","jpeg").contains(ext)){
            boolean png=bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G';boolean jpeg=bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255;
            if((ext.equals("png")&&!png)||(!ext.equals("png")&&!jpeg))throw new ProcessingException("FAILED","图像实际格式与扩展名不符");
            try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(bytes))){var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException();var reader=readers.next();try{reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||(long)width*height>16_000_000)throw new IllegalArgumentException();}finally{reader.dispose();}}catch(Exception e){throw new ProcessingException("FAILED","图像无效或像素超过1600万上限");}
            return media(ocr.recognize(name,bytes));
        }
        if(Set.of("wav","mp3","mp4","webm","m4a").contains(ext)){
            boolean riff=bytes.length>12&&new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE");
            boolean iso=bytes.length>12&&new String(bytes,4,4,java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp");boolean mp3=bytes.length>3&&((bytes[0]=='I'&&bytes[1]=='D'&&bytes[2]=='3')||(bytes[0]==(byte)255&&(bytes[1]&0xe0)==0xe0));boolean webm=bytes.length>4&&bytes[0]==0x1a&&bytes[1]==0x45&&bytes[2]==(byte)0xdf&&bytes[3]==(byte)0xa3;
            if(!(ext.equals("wav")?riff:ext.equals("mp3")?mp3:ext.equals("webm")?webm:iso))throw new ProcessingException("FAILED","音视频实际格式与扩展名不符");return media(transcription.transcribe(name,bytes));
        }
        var result=parser.parse(name,bytes);if(result.code()==ParseCode.NO_TEXT){if(ext.equals("pdf")&&ocr.availability()==ProviderAvailability.AVAILABLE)return media(ocr.recognize(name,bytes));throw new ProcessingException("NO_TEXT","未提取到正文；扫描PDF需要配置OCR");}if(result.code()!=ParseCode.SUCCESS)throw new ProcessingException("FAILED",result.message());return result.sections();
    }
    private static List<TextSection> media(ProviderResult<List<TextSection>> result){if(result.state()!=ProviderResult.State.SUCCESS)throw new ProcessingException(result.state()==ProviderResult.State.UNAVAILABLE?"DEPENDENCY_UNAVAILABLE":"FAILED",result.message());return result.value();}
}
