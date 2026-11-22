package com.retail.agent.context;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.poi.xwpf.usermodel.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.retail.agent.context.EnterpriseDocument.*;

class DocumentExtractionTest {
    @TempDir Path root;
    DocumentExtractor extractor(){return new DocumentExtractor(root,1000000,50000);}
    @Test void textAndMarkdownNormalizeAndPreserveLocations()throws Exception{
        Files.writeString(root.resolve("retail.txt"),"Inventory\r\n\r\nATP\t equals  on-hand minus reserved.");
        var text=extractor().extract(Path.of("retail.txt"),"text/plain");assertThat(text.segments()).hasSize(2);assertThat(text.segments().get(1).text()).isEqualTo("ATP equals on-hand minus reserved.");
        Files.writeString(root.resolve("retail.md"),"# Replenishment\n\nInventory proposals are advisory.");var md=extractor().extract(Path.of("retail.md"),"text/markdown");assertThat(md.segments().get(1).location().section()).isEqualTo("Replenishment");
    }
    static void pdf(Path file,boolean encrypted)throws Exception{
        try(var document=new PDDocument()){var page=new PDPage();document.addPage(page);try(var content=new PDPageContentStream(document,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText("Inventory approval requires recorded evidence.");content.endText();}
            if(encrypted)document.protect(new StandardProtectionPolicy("owner-secret","reader-secret",new AccessPermission()));document.save(file.toFile());}
    }
    @Test void pdfExtractionUsesRealParserAndPageCitation()throws Exception{pdf(root.resolve("retail.pdf"),false);var extracted=extractor().extract(Path.of("retail.pdf"),"application/pdf");assertThat(extracted.segments()).singleElement().satisfies(s->{assertThat(s.location().kind()).isEqualTo("page");assertThat(s.location().ordinal()).isEqualTo(1);assertThat(s.text()).contains("recorded evidence");});}
    @Test void docxExtractionUsesRealParserAndParagraphLocations()throws Exception{
        try(var document=new XWPFDocument()){document.createParagraph().createRun().setText("Inventory thresholds require review.");document.createParagraph().createRun().setText("Never place purchase orders automatically.");try(var out=Files.newOutputStream(root.resolve("retail.docx"))){document.write(out);}}
        var e=extractor().extract(Path.of("retail.docx"),"application/vnd.openxmlformats-officedocument.wordprocessingml.document");assertThat(e.segments()).hasSize(2);assertThat(e.segments().get(1).location().ordinal()).isEqualTo(2);
    }
    @Test void corruptUnsupportedEncryptedAndDisguisedFilesAreRejected()throws Exception{
        Files.writeString(root.resolve("bad.pdf"),"%PDF-corrupt");assertThatThrownBy(()->extractor().extract(Path.of("bad.pdf"),"application/pdf")).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(root.resolve("bad.docx"),"PK corrupt");assertThatThrownBy(()->extractor().extract(Path.of("bad.docx"),"application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isInstanceOf(IllegalArgumentException.class);
        pdf(root.resolve("locked.pdf"),true);assertThatThrownBy(()->extractor().extract(Path.of("locked.pdf"),"application/pdf")).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(root.resolve("image.txt"),"%PDF-pretending");assertThatThrownBy(()->extractor().extract(Path.of("image.txt"),"text/plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->extractor().extract(Path.of("bad.pdf"),"application/javascript")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pathTraversalAbsolutePathsBinaryAndLimitsAreRejected()throws Exception{
        Files.write(root.resolve("binary.txt"),new byte[]{0,1,2});assertThatThrownBy(()->extractor().extract(Path.of("binary.txt"),"text/plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->extractor().extract(root.resolve("binary.txt"),"text/plain")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->extractor().extract(Path.of("..","outside.txt"),"text/plain")).isInstanceOf(SecurityException.class);
        Files.writeString(root.resolve("large.txt"),"x".repeat(50001));assertThatThrownBy(()->extractor().extract(Path.of("large.txt"),"text/plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new DocumentExtractor(root,10,100).extract(Path.of("large.txt"),"text/plain")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void chunksAreStableVersionedAndKeepOffsetsWithoutSurrogateSplits()throws Exception{
        Files.writeString(root.resolve("retail.txt"),"Inventory policy advisory only. ".repeat(15));var e=extractor().extract(Path.of("retail.txt"),"text/plain");var chunker=new DocumentChunker(64,16);UUID id=UUID.randomUUID();var chunks=chunker.chunks("tenant-a",id,1,e);
        assertThat(chunks).isEqualTo(chunker.chunks("tenant-a",id,1,e));assertThat(chunks.get(1).start()).isEqualTo(48);assertThat(chunks.get(0).id()).isNotEqualTo(chunker.chunks("tenant-a",id,2,e).get(0).id());assertThat(chunks).allMatch(c->c.text().length()<=64);
        assertThatThrownBy(()->new DocumentChunker(64,32)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void docxExternalRelationshipsAndEntityDeclarationsAreRejectedBeforeParsing()throws Exception{
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(root.resolve("external.docx")))){
            for(var e:Map.of("[Content_Types].xml","<Types/>","word/document.xml","<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///private'>]><document/>","word/_rels/document.xml.rels","<Relationships><Relationship TargetMode=\"External\" Target=\"https://untrusted.invalid\"/></Relationships>").entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        }
        assertThatThrownBy(()->extractor().extract(Path.of("external.docx"),"application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
    }
}
