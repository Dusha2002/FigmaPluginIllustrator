package com.figma.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
public class ImageInputLoader {

    private static final Logger logger = LoggerFactory.getLogger(ImageInputLoader.class);

    public BufferedImage read(byte[] data) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("Не удалось прочитать изображение.");
            }
            return image;
        } catch (IOException ex) {
            logger.error("Ошибка чтения изображения", ex);
            throw ex;
        }
    }
}
