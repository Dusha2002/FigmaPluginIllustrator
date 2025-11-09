package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import org.springframework.stereotype.Service;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

@Service
public class ImageProcessingService {

    private final ColorProfileManager colorProfileManager;

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
