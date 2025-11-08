package com.figma.export.color;

import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@Component
public class ColorProfileManager {

    private final ICC_Profile cmykProfile;
    private final ICC_ColorSpace cmykColorSpace;
    private final byte[] cmykProfileBytes;

    public ColorProfileManager() {
        try (InputStream stream = new ClassPathResource("profiles/CoatedFOGRA39.icc").getInputStream()) {
            byte[] bytes = IOUtils.toByteArray(Objects.requireNonNull(stream));
            this.cmykProfileBytes = bytes;
            this.cmykProfile = ICC_Profile.getInstance(bytes);
            this.cmykColorSpace = new ICC_ColorSpace(cmykProfile);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось загрузить ICC-профиль CoatedFOGRA39.", e);
        }
    }

    public ICC_Profile getCmykProfile() {
        return cmykProfile;
    }

    public ColorSpace getCmykColorSpace() {
        return cmykColorSpace;
    }

    public byte[] getCmykProfileBytes() {
        return cmykProfileBytes.clone();
    }
}
