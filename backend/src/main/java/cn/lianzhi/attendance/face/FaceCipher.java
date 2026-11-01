package cn.lianzhi.attendance.face;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** AES-256-GCM with per-write nonce and identity/version AAD. No plaintext feature fallback. */
final class FaceCipher {
    private final byte[] key;
    FaceCipher(String configuredKey) {
        byte[] parsed;
        try { parsed=Base64.getDecoder().decode(configuredKey); }
        catch (RuntimeException e) { parsed=new byte[0]; }
        key=parsed.length==32 ? parsed : null;
    }
    boolean ready() { return key!=null; }
    String encrypt(byte[] feature, String context) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(feature), packed=Arrays.copyOf(nonce,nonce.length+encrypted.length);
            System.arraycopy(encrypted,0,packed,nonce.length,encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) { throw new IllegalStateException("人脸特征加密失败"); }
    }
    byte[] decrypt(String stored, String context) {
        try {
            byte[] packed=Base64.getDecoder().decode(stored);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(packed,12,packed.length-12);
        } catch (Exception e) { throw new IllegalStateException("人脸特征不可读取，请检查密钥或重新登记"); }
    }
}
