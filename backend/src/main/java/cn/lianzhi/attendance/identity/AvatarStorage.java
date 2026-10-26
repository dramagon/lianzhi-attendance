package cn.lianzhi.attendance.identity;

import cn.lianzhi.attendance.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 原图不落盘；按数据库命名空间隔离，避免不同环境同ID用户共享头像。 */
@Service
public class AvatarStorage {
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private final Path directory;
    public AvatarStorage(@Value("${lianzhi.avatar-directory:./data/avatars}") String directory,
                         @Value("${spring.datasource.url}") String databaseUrl) {
        try {
            String namespace=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(databaseUrl.getBytes(StandardCharsets.UTF_8))).substring(0,16);
            this.directory=Path.of(directory).toAbsolutePath().normalize().resolve(namespace);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private Path file(Long userId) { return directory.resolve(userId + ".jpg"); }
    public String url(Long userId) {
        if (!Files.isRegularFile(file(userId))) return null;
        try {
            String version=URLEncoder.encode(Files.getLastModifiedTime(file(userId)).toString(),StandardCharsets.UTF_8);
            return "/api/profile/avatars/"+userId+"?v="+version;
        } catch (IOException error) { return null; }
    }
    public void save(Long userId, MultipartFile upload) {
        if (upload.isEmpty() || upload.getSize()>MAX_BYTES) throw BusinessException.invalid("头像不能为空且不能超过2MB");
        BufferedImage original;
        try (var stream=upload.getInputStream(); var input=new MemoryCacheImageInputStream(stream)) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw BusinessException.invalid("请选择有效的JPG或PNG图片");
            var reader=readers.next();
            try {
                if (!Set.of("jpeg","jpg","png").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw BusinessException.invalid("头像只支持JPG或PNG图片");
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if (width<1 || height<1 || width>4096 || height>4096) throw BusinessException.invalid("头像宽高须在1～4096像素内");
                original=reader.read(0);
                if (original==null) throw BusinessException.invalid("无法读取图片，请更换图片");
            } finally { reader.dispose(); }
        } catch (IOException error) { throw BusinessException.invalid("无法读取图片，请选择有效的JPG或PNG图片"); }
        var normalized=new BufferedImage(320,320,BufferedImage.TYPE_INT_RGB);
        var graphics=normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0,0,320,320);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int edge=Math.min(original.getWidth(),original.getHeight());
            int x=(original.getWidth()-edge)/2,y=(original.getHeight()-edge)/2;
            graphics.drawImage(original,0,0,320,320,x,y,x+edge,y+edge,null);
        } finally { graphics.dispose(); }
        Path temporary=null;
        try {
            Files.createDirectories(directory);
            temporary=Files.createTempFile(directory,userId+"-",".tmp");
            try (var output=Files.newOutputStream(temporary)) { ImageIO.write(normalized,"jpeg",output); }
            try { Files.move(temporary,file(userId),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary,file(userId),StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,"头像保存失败，请稍后重试"); }
        finally { if (temporary!=null) { try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} } }
    }
    public byte[] read(Long userId) {
        try { return Files.readAllBytes(file(userId)); }
        catch (NoSuchFileException error) { throw BusinessException.missing(); }
        catch (IOException error) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,"头像暂时无法读取"); }
    }
    public void remove(Long userId) {
        try { Files.deleteIfExists(file(userId)); }
        catch (IOException error) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,"头像暂时无法移除"); }
    }
}
