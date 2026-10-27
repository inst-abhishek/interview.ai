package com.exl.interview.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypts recoverable invitation credentials; the key stays outside the database. */
@Component
public class InvitationLinks {
  private final Path keyFile;
  private final SecureRandom random=new SecureRandom();
  public InvitationLinks(@Value("${interview.invitation-key-file:./data/invitation-link.key}") String keyFile){this.keyFile=Path.of(keyFile).toAbsolutePath();}
  private synchronized SecretKeySpec key() throws Exception {
    if(!Files.exists(keyFile)){
      Files.createDirectories(keyFile.getParent());byte[] key=new byte[32];random.nextBytes(key);
      try{Files.write(keyFile,key,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
    }
    byte[] key=Files.readAllBytes(keyFile);if(key.length!=32)throw new IllegalStateException("Invalid invitation encryption key");return new SecretKeySpec(key,"AES");
  }
  public String encrypt(String code){try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,nonce));byte[] encrypted=cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));byte[] combined=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,combined,0,nonce.length);System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);return Base64.getEncoder().encodeToString(combined);}catch(Exception e){throw new IllegalStateException("Could not store invitation link",e);}}
  public String decrypt(String value){try{byte[] combined=Base64.getDecoder().decode(value);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,combined,0,12));return new String(cipher.doFinal(combined,12,combined.length-12),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Invitation key unavailable; regenerate this invitation",e);}}
}
