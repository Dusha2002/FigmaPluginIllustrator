package com.figma.export.svg;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.util.XMLResourceDescriptor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D;
import org.springframework.stereotype.Component;
import org.w3c.dom.svg.SVGDocument;

import java.awt.geom.AffineTransform;
import java.awt.geom.Dimension2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
public class SvgRenderer {

    public SvgRenderResult renderSvg(byte[] svgBytes, PDDocument document, float targetWidthPt, float targetHeightPt) throws IOException {
        SVGDocument svgDocument = loadDocument(svgBytes);
        BridgeContext bridgeContext = new BridgeContext(new UserAgentAdapter());
        bridgeContext.setDynamicState(BridgeContext.DYNAMIC);

        GraphicsNode graphicsNode;
        Dimension2D documentSize;
        try {
            graphicsNode = new GVTBuilder().build(bridgeContext, svgDocument);
            documentSize = bridgeContext.getDocumentSize();
        } finally {
            bridgeContext.dispose();
        }

        double documentWidthPx = documentSize != null ? documentSize.getWidth() : 0;
        double documentHeightPx = documentSize != null ? documentSize.getHeight() : 0;

        float widthPt = targetWidthPt > 0 ? targetWidthPt : (float) pxToPoints(documentWidthPx);
        float heightPt = targetHeightPt > 0 ? targetHeightPt : (float) pxToPoints(documentHeightPx);
        if (widthPt <= 0 || Float.isNaN(widthPt)) {
            widthPt = PDRectangle.A4.getWidth();
        }
        if (heightPt <= 0 || Float.isNaN(heightPt)) {
            heightPt = PDRectangle.A4.getHeight();
        }

        PDRectangle pageSize = new PDRectangle(widthPt, heightPt);
        PDPage page = new PDPage(pageSize);
        document.addPage(page);

        PdfBoxGraphics2D graphics2D = new PdfBoxGraphics2D(document, widthPt, heightPt);
        AffineTransform transform = new AffineTransform();
        if (documentWidthPx > 0 && documentHeightPx > 0) {
            transform.scale(widthPt / documentWidthPx, heightPt / documentHeightPx);
        }
        graphics2D.transform(transform);
        graphicsNode.paint(graphics2D);
        graphics2D.dispose();
        PDFormXObject form = graphics2D.getXFormObject();

        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.drawForm(form);
        }

        return new SvgRenderResult(page, widthPt, heightPt);
    }

    public BufferedImageTranscoderResult rasterize(byte[] svgBytes, float widthPx, float heightPx) throws IOException {
        BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
        if (widthPx > 0) {
            transcoder.addTranscodingHint(BufferedImageTranscoder.KEY_WIDTH, widthPx);
        }
        if (heightPx > 0) {
            transcoder.addTranscodingHint(BufferedImageTranscoder.KEY_HEIGHT, heightPx);
        }
        try {
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svgBytes)), (TranscoderOutput) null);
        } catch (TranscoderException e) {
            throw new IOException("Не удалось растеризовать SVG", e);
        }
        return new BufferedImageTranscoderResult(transcoder.getBufferedImage(), transcoder.getWidth(), transcoder.getHeight());
    }

    private SVGDocument loadDocument(byte[] svgBytes) throws IOException {
        String parser = XMLResourceDescriptor.getXMLParserClassName();
        SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(parser);
        return factory.createSVGDocument(null, new ByteArrayInputStream(svgBytes));
    }

    private double pxToPoints(double px) {
        // SVG units по умолчанию основаны на 96 dpi
        return px * 72d / 96d;
    }

    public record SvgRenderResult(PDPage page, float widthPt, float heightPt) {
    }

    public record BufferedImageTranscoderResult(java.awt.image.BufferedImage image, float widthPx, float heightPx) {
    }

    private static class BufferedImageTranscoder extends org.apache.batik.transcoder.image.ImageTranscoder {
        private java.awt.image.BufferedImage bufferedImage;
        private float width;
        private float height;

        @Override
        public java.awt.image.BufferedImage createImage(int w, int h) {
            this.width = w;
            this.height = h;
            return new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(java.awt.image.BufferedImage img, org.apache.batik.transcoder.TranscoderOutput output) {
            this.bufferedImage = img;
        }

        public java.awt.image.BufferedImage getBufferedImage() {
            return bufferedImage;
        }

        public float getWidth() {
            return width;
        }

        public float getHeight() {
            return height;
        }
    }
}
