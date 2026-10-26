package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.client.MultiEmbeddingClient.EmbeddingInput;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

/** Assembles durable source locations before any embedding request. */
@Service
public class KnowledgeSourceAssembler {
    private final DocumentVectorRepository vectors;
    private final PdfImageExtractionService images;
    private final UploadService uploads;
    private final FileTypeValidationService fileTypes;
    public KnowledgeSourceAssembler(DocumentVectorRepository vectors,PdfImageExtractionService images,UploadService uploads,FileTypeValidationService fileTypes) {
        this.vectors=vectors;this.images=images;this.uploads=uploads;this.fileTypes=fileTypes;
    }
    public List<Chunk> assemble(FileUpload file) throws Exception {
        List<Chunk> result=new ArrayList<>();
        if(fileTypes.isSupportedImageFileName(file.getFileName())) {
            result.add(new Chunk("image:standalone","STANDALONE_IMAGE","图片："+file.getFileName(),null,null,null,null,"MINIO_OBJECT","uploads","merged/"+file.getFileMd5(),null,null,null));
            return List.copyOf(result);
        }
        var chunks=vectors.findByFileMd5OrderByChunkIdAsc(file.getFileMd5());
        // Older parsers may have stored the same physical content for several owners; stable chunk keys deduplicate it.
        Set<String> seen=new HashSet<>(); Map<Integer,StringBuilder> pageText=new HashMap<>();
        for(var c:chunks) if(c.getTextContent()!=null && !c.getTextContent().isBlank() && seen.add("text:"+c.getChunkId())) {
            result.add(new Chunk("text:"+c.getChunkId(),"TEXT_CHUNK",c.getTextContent(),c.getChunkId(),c.getPageNumber(),c.getAnchorText(),null,null,null,null,null,null,null));
            if(c.getPageNumber()!=null) pageText.computeIfAbsent(c.getPageNumber(),k->new StringBuilder()).append(c.getTextContent()).append('\n');
        }
        if(file.getFileName().toLowerCase(Locale.ROOT).endsWith(".pdf")) try(var stream=uploads.getMergedFileStream(file.getFileMd5())) {
            for(var image:images.extractImages(stream,200)) {
                String context=pageText.getOrDefault(image.pageNumber(),new StringBuilder()).toString();
                if(context.length()>1200) context=context.substring(0,1200);
                result.add(new Chunk("pdf-image:"+image.pageNumber()+":"+image.imageSequence(),"DOCUMENT_IMAGE",
                    "图片："+file.getFileName()+" 第 "+image.pageNumber()+" 页\n"+context,null,image.pageNumber(),null,image.imageSequence(),"DOCUMENT_EMBEDDED","uploads","merged/"+file.getFileMd5(),image.contentType(),image.width(),image.height()));
            }
        }
        if(result.isEmpty()) throw new IllegalStateException("没有可封存的文本或图片来源");
        return List.copyOf(result);
    }
    public EmbeddingInput embeddingInput(FileUpload file,Chunk chunk) throws Exception {
        if(!chunk.image()) return EmbeddingInput.text(chunk.text());
        if("DOCUMENT_EMBEDDED".equals(chunk.imageSourceType())) try(var stream=uploads.getMergedFileStream(file.getFileMd5())) {
            return EmbeddingInput.imageWithText(chunk.text(),images.extractOne(stream,chunk.pageNumber(),chunk.imageSequence()).toDataUrl());
        }
        return EmbeddingInput.imageWithText(chunk.text(),uploads.transToPublicUrl(uploads.generateMergedObjectUrl(file.getFileMd5())));
    }
}
