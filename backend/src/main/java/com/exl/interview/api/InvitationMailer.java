package com.exl.interview.api;

import java.util.*;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.SimpleMailMessage;

@Component
public class InvitationMailer {
 @Value("${interview.email-enabled:false}") private boolean enabled;
 @Value("${interview.smtp-host:}") private String host;
 @Value("${interview.smtp-port:587}") private int port;
 @Value("${interview.smtp-username:}") private String username;
 @Value("${interview.smtp-password:}") private String password;
 @Value("${interview.smtp-starttls:true}") private boolean starttls;
 @Value("${interview.email-from:}") private String from;
 @Value("${interview.public-app-url:http://127.0.0.1:4200}") private String publicUrl;
 public boolean available(){try{URI uri=URI.create(publicUrl);return enabled&&port>0&&port<=65535&&!host.isBlank()&&from.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")&&Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getFragment()==null;}catch(Exception e){return false;}}
 public void send(String email,String name,String title,long scheduled,long expires,String code,boolean reminder){
  if(!available())throw new IllegalStateException("Email unavailable");
  JavaMailSenderImpl sender=new JavaMailSenderImpl();sender.setDefaultEncoding("UTF-8");sender.setHost(host);sender.setPort(port);sender.setUsername(username);sender.setPassword(password);Properties props=sender.getJavaMailProperties();props.setProperty("mail.smtp.auth",Boolean.toString(!username.isBlank()));props.setProperty("mail.smtp.starttls.enable",Boolean.toString(starttls));props.setProperty("mail.smtp.starttls.required",Boolean.toString(starttls));props.setProperty("mail.smtp.connectiontimeout","10000");props.setProperty("mail.smtp.timeout","10000");props.setProperty("mail.smtp.writetimeout","10000");props.setProperty("mail.smtp.ssl.checkserveridentity","true");
  SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject((reminder?"Reminder: ":"Assessment invitation: ")+title.replaceAll("[\\r\\n]"," "));
  message.setText("Hello "+name+",\n\nYou are invited to complete "+title+".\nScheduled start (UTC): "+java.time.Instant.ofEpochMilli(scheduled)+"\nInvitation expires (UTC): "+java.time.Instant.ofEpochMilli(expires)+"\n\nOpen your personal assessment link:\n"+publicUrl+"#invite="+java.net.URLEncoder.encode(code,java.nio.charset.StandardCharsets.UTF_8)+"\n\nKeep this link private. Contact your recruiter with any questions.");sender.send(message);
 }
}
