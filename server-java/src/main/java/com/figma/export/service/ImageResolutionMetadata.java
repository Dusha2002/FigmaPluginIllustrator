package com.figma.export.service;

import org.springframework.stereotype.Service;

import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

@Service
public class ImageResolutionMetadata {

    private static final String NODE_TIFF_IFD = "TIFFIFD";
    private static final String NODE_TIFF_FIELD = "TIFFField";
    private static final String NODE_TIFF_RATIONALS = "TIFFRationals";
    private static final String NODE_TIFF_RATIONAL = "TIFFRational";
    private static final String NODE_TIFF_SHORTS = "TIFFShorts";
    private static final String NODE_TIFF_SHORT = "TIFFShort";
    private static final String NAME_X_RESOLUTION = "XResolution";
    private static final String NAME_Y_RESOLUTION = "YResolution";
    private static final String NAME_RESOLUTION_UNIT = "ResolutionUnit";
    private static final int TAG_X_RESOLUTION = 282;
    private static final int TAG_Y_RESOLUTION = 283;
    private static final int TAG_RESOLUTION_UNIT = 296;
    private static final int RESOLUTION_UNIT_INCH = 2;

    public void apply(IIOMetadata metadata, int ppi) throws IIOInvalidTreeException {
        if (metadata == null || ppi <= 0) {
            return;
        }
        double pixelSize = 25.4 / ppi; // мм на пиксель

        applyStandardMetadata(metadata, pixelSize);

        String nativeFormat = metadata.getNativeMetadataFormatName();
        if (nativeFormat != null && nativeFormat.contains("tiff")) {
            applyNativeTiffResolution(metadata, nativeFormat, ppi);
        }
    }

    private void applyStandardMetadata(IIOMetadata metadata, double pixelSize) throws IIOInvalidTreeException {
        try {
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_1.0");
            IIOMetadataNode dimension = new IIOMetadataNode("Dimension");

            IIOMetadataNode horizontal = new IIOMetadataNode("HorizontalPixelSize");
            horizontal.setAttribute("value", Double.toString(pixelSize));
            dimension.appendChild(horizontal);

            IIOMetadataNode vertical = new IIOMetadataNode("VerticalPixelSize");
            vertical.setAttribute("value", Double.toString(pixelSize));
            dimension.appendChild(vertical);

            root.appendChild(dimension);
            metadata.mergeTree("javax_imageio_1.0", root);
        } catch (IllegalArgumentException ignored) {
            // стандартное дерево недоступно для данного writer
        }
    }

    private void applyNativeTiffResolution(IIOMetadata metadata, String nativeFormat, int ppi) {
        try {
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(nativeFormat);
            IIOMetadataNode ifd = getOrCreateNode(root, NODE_TIFF_IFD);

            replaceTiffField(ifd, TAG_X_RESOLUTION, createRationalField(TAG_X_RESOLUTION, NAME_X_RESOLUTION, ppi, 1));
            replaceTiffField(ifd, TAG_Y_RESOLUTION, createRationalField(TAG_Y_RESOLUTION, NAME_Y_RESOLUTION, ppi, 1));
            replaceTiffField(ifd, TAG_RESOLUTION_UNIT, createShortField(TAG_RESOLUTION_UNIT, NAME_RESOLUTION_UNIT, RESOLUTION_UNIT_INCH));

            metadata.setFromTree(nativeFormat, root);
        } catch (Exception ignored) {
            // если структура неожиданная, нативные поля пропускаем
        }
    }

    private IIOMetadataNode getOrCreateNode(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i) instanceof IIOMetadataNode node && name.equals(node.getNodeName())) {
                return node;
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    private void replaceTiffField(IIOMetadataNode ifd, int tagNumber, IIOMetadataNode newField) {
        removeTiffField(ifd, tagNumber);
        ifd.appendChild(newField);
    }

    private void removeTiffField(IIOMetadataNode ifd, int tagNumber) {
        for (int i = ifd.getLength() - 1; i >= 0; i--) {
            if (ifd.item(i) instanceof IIOMetadataNode node && NODE_TIFF_FIELD.equals(node.getNodeName())) {
                String number = node.getAttribute("number");
                if (Integer.toString(tagNumber).equals(number)) {
                    ifd.removeChild(node);
                }
            }
        }
    }

    private IIOMetadataNode createRationalField(int tagNumber, String name, long numerator, long denominator) {
        IIOMetadataNode field = new IIOMetadataNode(NODE_TIFF_FIELD);
        field.setAttribute("number", Integer.toString(tagNumber));
        field.setAttribute("name", name);
        field.setAttribute("type", "5");
        field.setAttribute("count", "1");
        IIOMetadataNode rationals = new IIOMetadataNode(NODE_TIFF_RATIONALS);
        IIOMetadataNode rational = new IIOMetadataNode(NODE_TIFF_RATIONAL);
        rational.setAttribute("value", numerator + "/" + denominator);
        rational.setAttribute("numerator", Long.toString(numerator));
        rational.setAttribute("denominator", Long.toString(denominator));
        rationals.appendChild(rational);
        field.appendChild(rationals);
        return field;
    }

    private IIOMetadataNode createShortField(int tagNumber, String name, int value) {
        IIOMetadataNode field = new IIOMetadataNode(NODE_TIFF_FIELD);
        field.setAttribute("number", Integer.toString(tagNumber));
        field.setAttribute("name", name);
        field.setAttribute("type", "3");
        field.setAttribute("count", "1");
        IIOMetadataNode shorts = new IIOMetadataNode(NODE_TIFF_SHORTS);
        IIOMetadataNode shortNode = new IIOMetadataNode(NODE_TIFF_SHORT);
        shortNode.setAttribute("value", Integer.toString(value));
        shorts.appendChild(shortNode);
        field.appendChild(shorts);
        return field;
    }
}
