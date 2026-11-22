package com.retail.agent.context;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static com.retail.agent.context.EnterpriseDocument.*;

/** Local allow-root only; no URI fetch, macros, link traversal, OCR or embedded object execution. */
public final class DocumentExtractor {
    private final Path root;private final int maximumBytes,maximumCharacters;
    public DocumentExtractor(Path root,int maximumBytes,int maximumCharacters){
        if(maximumBytes<1||maximumBytes>10_000_000||maximumCharacters<1||maximumCharacters>1_000_000)throw new IllegalArgumentException("Invalid extraction limits");
        try{this.root=root.toRealPath();}catch(IOException e){throw new IllegalArgumentException("Ingestion root unavailable");}
        this.maximumBytes=maximumBytes;this.maximumCharacters=maximumCharacters;
    }
    public Extracted extract(Path relative,String mime){
        try{
            if(relative.isAbsolute()||relative.toString().contains(":")||relative.toString().contains(".."))throw new SecurityException("Relative document name required");
            Path path=root.resolve(relative).normalize();if(!path.startsWith(root))throw new SecurityException("Document path escaped root");
            Path step=root;for(Path part:root.relativize(path)){step=step.resolve(part);if(Files.isSymbolicLink(step)||!step.toRealPath().startsWith(root))throw new SecurityException("Document links forbidden");}
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>maximumBytes)throw new IllegalArgumentException("Document size/type rejected");
            byte[] bytes;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(maximumBytes+1);}if(bytes.length==0||bytes.length>maximumBytes)throw new IllegalArgumentException("Document size rejected");
            var segments=new ArrayList<Segment>();String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
            switch(mime){
                case "text/plain","text/markdown" -> {
                    if(bytes.length>=4&&(bytes[0]=='P'&&bytes[1]=='K'||new String(bytes,0,Math.min(5,bytes.length),StandardCharsets.US_ASCII).startsWith("%PDF")))throw new IllegalArgumentException("Container disguised as text");
                    if(!(mime.equals("text/plain")&&name.endsWith(".txt")||mime.equals("text/markdown")&&name.endsWith(".md")))throw new IllegalArgumentException("Document extension mismatch");
                    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    if(text.chars().anyMatch(c->c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'&&c!='\f'))throw new IllegalArgumentException("Binary text rejected");
                    int ordinal=0;String section="";for(String p:text.split("\\R\\s*\\R")){ordinal++;if(mime.equals("text/markdown")&&p.strip().startsWith("#"))section=p.strip().lines().findFirst().orElse("").replaceFirst("^#+\\s*","");add(segments,new Location("paragraph",ordinal,section.substring(0,Math.min(200,section.length()))),p);}
                }
                case "application/pdf" -> {
                    if(!name.endsWith(".pdf")||bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("PDF signature rejected");
                    try(var pdf=Loader.loadPDF(bytes)){
                        if(pdf.isEncrypted()||pdf.getNumberOfPages()>500)throw new IllegalArgumentException("Encrypted/oversized PDF rejected");
                        var stripper=new PDFTextStripper(){private int count;@Override protected void writeString(String text,List<org.apache.pdfbox.text.TextPosition> positions)throws IOException{count+=text.length();if(count>maximumCharacters)throw new IOException("PDF extracted text limit");super.writeString(text,positions);}};for(int page=1;page<=pdf.getNumberOfPages();page++){stripper.setStartPage(page);stripper.setEndPage(page);add(segments,new Location("page",page,""),stripper.getText(pdf));check(segments);}
                    }
                }
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                    if(!name.endsWith(".docx")||bytes[0]!='P'||bytes.length<4||bytes[1]!='K')throw new IllegalArgumentException("DOCX signature rejected");
                    validateZip(bytes);
                    try(var document=new XWPFDocument(new ByteArrayInputStream(bytes))){int ordinal=0;for(var element:document.getBodyElements()){
                        if(element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph p)add(segments,new Location("paragraph",++ordinal,""),p.getText());
                        else if(element instanceof org.apache.poi.xwpf.usermodel.XWPFTable t)for(var row:t.getRows())add(segments,new Location("paragraph",++ordinal,"table"),row.getTableCells().stream().map(c->c.getText()).collect(java.util.stream.Collectors.joining(" | ")));
                        check(segments);
                    }}
                }
                default -> throw new IllegalArgumentException("Unsupported document MIME");
            }
            check(segments);if(segments.isEmpty())throw new IllegalArgumentException("No extractable document text");
            return new Extracted(mime,hash(bytes),segments);
        }catch(SecurityException denied){throw new SecurityException("Document path/type policy rejected");}catch(Exception rejected){throw new IllegalArgumentException("Document extraction rejected; content omitted");}
    }
    private void validateZip(byte[] bytes)throws IOException{
        int entries=0,total=0;boolean contentTypes=false,word=false;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null){
            if(++entries>1000||e.getName().contains("..")||e.getName().startsWith("/")||e.getName().contains("\\")||e.getName().toLowerCase(Locale.ROOT).contains("vbaproject"))throw new IllegalArgumentException("Unsafe DOCX archive");
            contentTypes|=e.getName().equals("[Content_Types].xml");word|=e.getName().equals("word/document.xml");
            byte[] buffer=new byte[8192];int count;var xml=new ByteArrayOutputStream();while((count=zip.read(buffer))!=-1){total+=count;if(total>Math.min(20_000_000,maximumBytes*20L))throw new IllegalArgumentException("DOCX expansion limit");if(e.getName().endsWith(".xml")||e.getName().endsWith(".rels"))xml.write(buffer,0,count);}
            String body=xml.toString(StandardCharsets.UTF_8);if(body.contains("<!DOCTYPE")||body.contains("<!ENTITY")||e.getName().endsWith(".rels")&&body.matches("(?s).*TargetMode\\s*=\\s*[\"']External[\"'].*"))throw new IllegalArgumentException("External DOCX references rejected");
        }}if(!contentTypes||!word)throw new IllegalArgumentException("Not a DOCX package");
    }
    private static void add(List<Segment> segments,Location location,String text){String normalized=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFC).replace("\uFEFF","").replace("\r\n","\n").replace('\r','\n').replaceAll("[\\t ]+"," ").strip();if(!normalized.isBlank())segments.add(new Segment(location,normalized));}
    private void check(List<Segment> segments){if(segments.stream().mapToLong(s->s.text().length()).sum()>maximumCharacters)throw new IllegalArgumentException("Extracted content limit exceeded");}
}
