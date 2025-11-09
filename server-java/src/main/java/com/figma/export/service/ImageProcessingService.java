package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.figma.export.model.TiffAntialias;
import org.springframework.stereotype.Service;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ComponentColorModel;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBuffer;
import java.awt.image.Kernel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

@Service
public class ImageProcessingService {

    private final ColorProfileManager colorProfileManager;

    private static final float[] FAST_KERNEL = {
            1f / 9f, 1f / 9f, 1f / 9f,
            1f / 9f, 1f / 9f, 1f / 9f,
            1f / 9f, 1f / 9f, 1f / 9f
    };

    private static final float[] BALANCED_KERNEL = {
            1f / 16f, 2f / 16f, 1f / 16f,
            2f / 16f, 4f / 16f, 2f / 16f,
            1f / 16f, 2f / 16f, 1f / 16f
    };

    private static final float[] BEST_KERNEL = {
            1f / 273f, 4f / 273f, 7f / 273f, 4f / 273f, 1f / 273f,
            4f / 273f, 16f / 273f, 26f / 273f, 16f / 273f, 4f / 273f,
            7f / 273f, 26f / 273f, 41f / 273f, 26f / 273f, 7f / 273f,
            4f / 273f, 16f / 273f, 26f / 273f, 16f / 273f, 4f / 273f,
            1f / 273f, 4f / 273f, 7f / 273f, 4f / 273f, 1f / 273f
    };

    public ImageProcessingService(ColorProfileManager colorProfileManager) {
        this.colorProfileManager = colorProfileManager;
    }

    public BufferedImage ensureArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public BufferedImage flattenTransparency(BufferedImage source, Color background) {
        if (!source.getColorModel().hasAlpha()) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(background);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public BufferedImage scaleImage(BufferedImage source, int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0 || (source.getWidth() == targetWidth && source.getHeight() == targetHeight)) {
            return source;
        }
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, source.getType());
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public BufferedImage convertToCmyk(BufferedImage sourceRgb) {
        return convertToCmyk(sourceRgb, null);
    }

    public BufferedImage convertToCmyk(BufferedImage sourceRgb, ColorProfile profile) {
        BufferedImage rgb = ensureRgb(sourceRgb);
        ColorProfile effectiveProfile = profile != null ? profile : colorProfileManager.getDefaultProfile();
        ICC_ColorSpace cmykSpace = effectiveProfile.getColorSpace();
        int width = rgb.getWidth();
        int height = rgb.getHeight();
        int[] bits = {8, 8, 8, 8};
        ColorSpace rgbSpace = rgb.getColorModel().getColorSpace();
        ComponentColorModel colorModel = new ComponentColorModel(cmykSpace, bits, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
        BufferedImage cmykImage = new BufferedImage(colorModel, raster, false, null);
        ColorConvertOp convertOp = new ColorConvertOp(rgbSpace, cmykSpace, null);
        convertOp.filter(rgb, cmykImage);
        return cmykImage;
    }

    public BufferedImage applyAntialias(BufferedImage source, TiffAntialias quality) {
        if (source == null || quality == null || quality == TiffAntialias.NONE) {
            return source;
        }
        return switch (quality) {
            case FAST -> applyKernel(source, FAST_KERNEL, 3, 1);
            case BALANCED -> applyKernel(source, BALANCED_KERNEL, 3, 1);
            case BEST -> applyKernel(source, BEST_KERNEL, 5, 2);
            case NONE -> source;
        };
    }

    private BufferedImage applyKernel(BufferedImage source, float[] kernelData, int size, int iterations) {
        BufferedImage current = ensureArgb(source);
        Kernel kernel = new Kernel(size, size, kernelData);
        for (int i = 0; i < iterations; i++) {
            BufferedImage target = new BufferedImage(current.getWidth(), current.getHeight(), BufferedImage.TYPE_INT_ARGB);
            ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
            op.filter(current, target);
            current = target;
        }
        return current;
    }

    private BufferedImage ensureRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
