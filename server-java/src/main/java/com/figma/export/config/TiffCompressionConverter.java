package com.figma.export.config;

import com.figma.export.model.TiffCompression;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class TiffCompressionConverter implements Converter<String, TiffCompression> {

    @Override
    public TiffCompression convert(String source) {
        return source == null ? null : TiffCompression.from(source);
    }
}
