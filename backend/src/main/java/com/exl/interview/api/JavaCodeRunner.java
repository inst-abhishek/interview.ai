package com.exl.interview.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

@Service
public class JavaCodeRunner {
 public record TestResult(String id,String state,String input,String expected,String output,long milliseconds){}
 public record Result(String state,int passed,int total,List<TestResult> tests,String message){}
 private final boolean enabled;private final String image;private final Semaphore slots=new Semaphore(2);
 public JavaCodeRunner(@Value("${interview.coding-enabled:false}")boolean enabled,@Value("${interview.coding-image:interview-java-runner:1}")String image){this.enabled=enabled;this.image=image;}
 public boolean enabled(){return enabled;}
 public Result run(String source,List<TemplateCatalogue.TestCase> tests){
  if(!enabled)return new Result("UNAVAILABLE",0,tests.size(),List.of(),"Docker coding execution is disabled. Enable it after the setup checks pass.");
  if(source==null||source.isBlank())return new Result("COMPLETE",0,tests.size(),List.of(),"No code was submitted.");
  if(source.length()>20000)return new Result("INFRASTRUCTURE_ERROR",0,tests.size(),List.of(),"Source exceeds the size limit.");
  if(!slots.tryAcquire())return new Result("BUSY",0,tests.size(),List.of(),"Coding workers are busy; retry later.");
  Path directory=null;var outcomes=new ArrayList<TestResult>();
  try{
   directory=Files.createTempDirectory("exl-java-");Files.writeString(directory.resolve("Main.java"),source,StandardCharsets.UTF_8);
   for(var test:tests){
    String name="exl-java-"+UUID.randomUUID();long start=System.nanoTime();
    var command=new ArrayList<>(List.of("docker","run","--pull=never","--rm","--name",name,"--interactive","--network","none","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--pids-limit","128","--memory","512m","--memory-swap","512m","--cpus","1","--ulimit","nofile=128:128","--user","65534:65534","--tmpfs","/work:rw,noexec,nosuid,nodev,size=64m,mode=1777","--tmpfs","/tmp:rw,noexec,nosuid,nodev,size=32m,mode=1777","--mount","type=bind,source="+directory.toAbsolutePath()+",target=/submission,readonly",image,"sh","-c","cp /submission/Main.java /work/Main.java && cd /work && { javac -J-Xmx128m Main.java >compile.log 2>&1 || { cat compile.log; exit 42; }; } && timeout 3s java -Xmx128m -XX:ActiveProcessorCount=1 -cp /work Main"));
    Process process=null;String output="";int exit;
    try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
     try{
      process=new ProcessBuilder(command).redirectErrorStream(true).start();Process running=process;
      Future<String> collected=threads.submit(()->{try(var stream=running.getInputStream();var bytes=new ByteArrayOutputStream()){byte[] block=new byte[1024];int n;while((n=stream.read(block))!=-1){if(bytes.size()+n>32768){running.destroyForcibly();return "Output exceeded 32 KB";}bytes.write(block,0,n);}return bytes.toString(StandardCharsets.UTF_8);}});
      threads.submit(()->{try(var stdin=running.getOutputStream()){stdin.write(test.input().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}});
      if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();exit=124;}else exit=process.exitValue();
      try{output=collected.get(2,TimeUnit.SECONDS);}catch(Exception ignored){output="Execution output unavailable";}
     }finally{if(process!=null&&process.isAlive())process.destroyForcibly();try{var cleanup=new ProcessBuilder("docker","rm","--force",name).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!cleanup.waitFor(5,TimeUnit.SECONDS))cleanup.destroyForcibly();}catch(Exception ignored){}}
    }
    if(exit==125||exit==126||exit==127)return new Result("INFRASTRUCTURE_ERROR",0,tests.size(),outcomes,"Docker worker or image unavailable. Verify Docker Desktop and build the runner image.");
    String state=output.equals("Output exceeded 32 KB")?"OUTPUT_LIMIT":exit==42?"COMPILATION_ERROR":exit==124?"TIME_LIMIT":exit!=0?"RUNTIME_ERROR":output.strip().equals(test.expected().strip())?"PASSED":"WRONG_ANSWER";
    outcomes.add(new TestResult(test.id(),state,test.input(),test.expected(),output.substring(0,Math.min(output.length(),4000)),(System.nanoTime()-start)/1_000_000));
    if(state.equals("COMPILATION_ERROR"))break;
   }
   return new Result("COMPLETE",(int)outcomes.stream().filter(t->t.state().equals("PASSED")).count(),tests.size(),outcomes,"Evaluation completed in isolated Docker containers.");
  }catch(Exception e){return new Result("INFRASTRUCTURE_ERROR",0,tests.size(),outcomes,"Docker execution could not start; verify the local coding worker setup.");}
  finally{slots.release();if(directory!=null){try(var files=Files.walk(directory)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException ignored){}}}
 }
}
