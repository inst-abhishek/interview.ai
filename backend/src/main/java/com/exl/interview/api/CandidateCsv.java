package com.exl.interview.api;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded RFC-style CSV parser supporting quoted commas, escaped quotes and multiline cells. */
final class CandidateCsv {
 static List<List<String>> parse(String content){
  if(content==null||content.length()>1_000_000||content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_000_000)throw bad("Choose a CSV file under 1 MB");
  String input=content.startsWith("\uFEFF")?content.substring(1):content;
  List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();
  boolean quoted=false,closed=false;
  for(int i=0;i<input.length();i++){
   char c=input.charAt(i);
   if(quoted){if(c=='"'){if(i+1<input.length()&&input.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);}
   else if(c=='"'){if(cell.length()!=0||closed)throw bad("Invalid quote in CSV row "+(rows.size()+1));quoted=true;}
   else if(c==','||c=='\n'||c=='\r'){
    row.add(cell.toString());cell.setLength(0);closed=false;
    if(c!=','){if(c=='\r'&&i+1<input.length()&&input.charAt(i+1)=='\n')i++;if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);row=new ArrayList<>();if(rows.size()>1001)throw bad("Import at most 1,000 candidates at a time");}
   }else{if(closed&&!Character.isWhitespace(c))throw bad("Unexpected text after a quoted CSV field");if(!closed)cell.append(c);}
   if(cell.length()>10000||row.size()>20)throw bad("CSV contains an oversized field or too many columns");
  }
  if(quoted)throw bad("A quoted CSV field was not closed");
  if(cell.length()>0||!row.isEmpty()||closed){row.add(cell.toString());if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);}
  if(rows.isEmpty()||rows.size()>1001)throw bad("CSV must contain a header and at most 1,000 candidates");return rows;
 }
 static String exportCell(String value){String s=value==null?"":value;if(!s.isEmpty()&&("=+-@\t\r\n".indexOf(s.charAt(0))>=0||s.stripLeading().matches("^[=+@-].*")))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
 static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
