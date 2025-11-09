package com.figma.export.config;

import com.figma.export.model.TiffAntialias;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class TiffAntialiasConverter implements Converter<String, TiffAntialias> {

    @Override
    public TiffAntialias convert(String source) {
        return source == null ? null : TiffAntialias.from(source);
    }
}
