package com.yizhaoqi.smartpai.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class PdfImageExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(PdfImageExtractionService.class);
    private static final String OUTPUT_FORMAT = "png";
    private static final String OUTPUT_CONTENT_TYPE = "image/png";

    public List<PdfEmbeddedImage> extractImages(InputStream inputStream, int maxImages) throws IOException {
        List<PdfEmbeddedImage> images = new ArrayList<>();
        try (PDDocument document = PDDocument.load(inputStream)) {
            int totalPages = document.getNumberOfPages();
            for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
                if (maxImages > 0 && images.size() >= maxImages) {
                    logger.warn("PDF 内嵌图片达到提取上限，停止继续提取: maxImages={}", maxImages);
                    break;
                }

                PDPage page = document.getPage(pageIndex);
                int pageNumber = pageIndex + 1;
                List<ExtractedImageBytes> pageImages = new ArrayList<>();
                extractImagesFromResources(page.getResources(), pageImages);
                for (int i = 0; i < pageImages.size(); i++) {
                    if (maxImages > 0 && images.size() >= maxImages) {
                        break;
                    }
                    ExtractedImageBytes image = pageImages.get(i);
                    images.add(new PdfEmbeddedImage(
                            pageNumber,
                            i + 1,
                            image.bytes(),
                            OUTPUT_CONTENT_TYPE,
                            image.width(),
                            image.height()
                    ));
                }
            }
        }
        return images;
    }

    public PdfEmbeddedImage extractOne(InputStream inputStream, int pageNumber, int imageSequence) throws IOException {
        if (pageNumber <= 0 || imageSequence <= 0) {
            throw new IllegalArgumentException("pageNumber 和 imageSequence 必须大于 0");
        }

        try (PDDocument document = PDDocument.load(inputStream)) {
            if (pageNumber > document.getNumberOfPages()) {
                throw new IllegalArgumentException("页码超出范围: " + pageNumber + "/" + document.getNumberOfPages());
            }

            PDPage page = document.getPage(pageNumber - 1);
            List<ExtractedImageBytes> pageImages = new ArrayList<>();
            extractImagesFromResources(page.getResources(), pageImages);
            if (imageSequence > pageImages.size()) {
                throw new IllegalArgumentException("图片序号超出范围: 第" + pageNumber + "页共有 " + pageImages.size() + " 张图片");
            }

            ExtractedImageBytes image = pageImages.get(imageSequence - 1);
            return new PdfEmbeddedImage(
                    pageNumber,
                    imageSequence,
                    image.bytes(),
                    OUTPUT_CONTENT_TYPE,
                    image.width(),
                    image.height()
            );
        }
    }

    private void extractImagesFromResources(PDResources resources, List<ExtractedImageBytes> images) throws IOException {
        if (resources == null) {
            return;
        }

        for (COSName xObjectName : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(xObjectName);
            if (xObject instanceof PDImageXObject imageXObject) {
                BufferedImage bufferedImage = imageXObject.getImage();
                if (bufferedImage == null) {
                    continue;
                }
                try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    ImageIO.write(bufferedImage, OUTPUT_FORMAT, outputStream);
                    images.add(new ExtractedImageBytes(
                            outputStream.toByteArray(),
                            bufferedImage.getWidth(),
                            bufferedImage.getHeight()
                    ));
                }
            } else if (xObject instanceof PDFormXObject formXObject) {
                extractImagesFromResources(formXObject.getResources(), images);
            }
        }
    }

    private record ExtractedImageBytes(byte[] bytes, int width, int height) {
    }

    public record PdfEmbeddedImage(
            int pageNumber,
            int imageSequence,
            byte[] bytes,
            String contentType,
            int width,
            int height
    ) {
        public String toDataUrl() {
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }
}
