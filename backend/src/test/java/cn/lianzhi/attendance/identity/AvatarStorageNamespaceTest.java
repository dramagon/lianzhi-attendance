package cn.lianzhi.attendance.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AvatarStorageNamespaceTest {
    @TempDir Path directory;
    private Path oldAvatar(String database) throws Exception {
        String namespace=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(database.getBytes(StandardCharsets.UTF_8))).substring(0,16);
        Path file=directory.resolve(namespace).resolve("7.jpg");Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(320,320,BufferedImage.TYPE_INT_RGB),"jpg",file.toFile());
        return file;
    }
    private AnnotationConfigApplicationContext context(String database,String key) {
        var properties=new HashMap<String,Object>();properties.put("lianzhi.avatar-directory",directory.toString());
        properties.put("spring.datasource.url",database);
        if(key!=null)properties.put("lianzhi.avatar-database-key",key);
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-test",properties));
        context.register(AvatarStorage.class);context.refresh();return context;
    }
    @Test void explicitSameDatabaseKeyPreservesAvatarAcrossConnectionTuning() throws Exception {
        String original="jdbc:h2:mem:legacy;MODE=MySQL";Path file=oldAvatar(original);byte[] before=Files.readAllBytes(file);
        try(var context=context(original+";OPTIMIZE_REUSE_RESULTS=FALSE",original)) {
            var avatars=context.getBean(AvatarStorage.class);
            assertThat(avatars.url(7L)).isNotNull();assertThat(avatars.read(7L)).isEqualTo(before);
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
        }
    }
    @Test void anotherDatabaseDoesNotReadExistingSameUserIdWithoutExplicitKey() throws Exception {
        Path file=oldAvatar("jdbc:h2:mem:other-company");
        try(var context=context("jdbc:h2:mem:new-company",null)) {
            assertThat(context.getBean(AvatarStorage.class).url(7L)).isNull();
            assertThat(Files.isRegularFile(file)).isTrue();
        }
    }
}
