package com.figma.export.color;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ColorProfileManager {

    private static final Logger logger = LoggerFactory.getLogger(ColorProfileManager.class);

    public static final String DEFAULT_PROFILE_ID = "coated_fogra39";

    private final Map<String, ColorProfile> profiles = new ConcurrentHashMap<>();

    public ColorProfileManager() {
        loadProfiles(List.of(
                ColorProfileDescriptor.builder()
                        .id(DEFAULT_PROFILE_ID)
                        .displayName("Coated FOGRA39")
                        .description("FOGRA39L CMYK Profile (ISO 12647-2:2004)")
                        .resourcePath("profiles/CoatedFOGRA39.icc")
                        .outputConditionIdentifier("Coated FOGRA39")
                        .outputCondition("Coated FOGRA39")
                        .build(),
                ColorProfileDescriptor.builder()
                        .id("iso_coated_v2")
                        .displayName("ISO Coated v2 (ECI)")
                        .description("Requires profiles/ISOcoated_v2_eci.icc on classpath")
                        .resourcePath("profiles/ISOcoated_v2_eci.icc")
                        .outputConditionIdentifier("ISOcoated_v2_eci")
                        .outputCondition("ISO Coated v2")
                        .build(),
                ColorProfileDescriptor.builder()
                        .id("us_web_coated_swop")
                        .displayName("US Web Coated (SWOP) v2")
                        .description("Requires profiles/USWebCoatedSWOP.icc on classpath")
                        .resourcePath("profiles/USWebCoatedSWOP.icc")
                        .outputConditionIdentifier("USWebCoatedSWOP")
                        .outputCondition("U.S. Web Coated (SWOP) v2")
                        .build()
        ));

        if (!profiles.containsKey(DEFAULT_PROFILE_ID)) {
            throw new IllegalStateException("Не удалось загрузить обязательный ICC-профиль: " + DEFAULT_PROFILE_ID);
        }
    }

    private void loadProfiles(Collection<ColorProfileDescriptor> descriptors) {
        for (ColorProfileDescriptor descriptor : descriptors) {
            if (descriptor.resourcePath() == null || descriptor.resourcePath().isBlank()) {
                continue;
            }
            Resource resource = new ClassPathResource(descriptor.resourcePath());
            if (!resource.exists()) {
                logger.warn("ICC-профиль '{}' ({}): ресурс '{}' не найден, профиль пропущен.",
                        descriptor.id(), descriptor.displayName(), descriptor.resourcePath());
                continue;
            }
            try (InputStream stream = resource.getInputStream()) {
                byte[] bytes = IOUtils.toByteArray(Objects.requireNonNull(stream));
                ICC_Profile iccProfile = ICC_Profile.getInstance(bytes);
                ICC_ColorSpace colorSpace = new ICC_ColorSpace(iccProfile);
                ColorProfile profile = new ColorProfile(
                        descriptor.id(),
                        descriptor.displayName(),
                        descriptor.description(),
                        descriptor.outputConditionIdentifier(),
                        descriptor.outputCondition(),
                        iccProfile,
                        colorSpace,
                        bytes
                );
                profiles.put(descriptor.id(), profile);
                logger.info("ICC-профиль '{}' загружен из '{}'.", descriptor.id(), descriptor.resourcePath());
            } catch (IOException ex) {
                logger.error("Не удалось загрузить ICC-профиль '{}' из '{}'.", descriptor.id(), descriptor.resourcePath(), ex);
            }
        }
    }

    public ColorProfile getDefaultProfile() {
        return Objects.requireNonNull(profiles.get(DEFAULT_PROFILE_ID), "default profile must be present");
    }

    public ColorProfile getProfileOrDefault(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return getDefaultProfile();
        }
        return profiles.getOrDefault(profileId.toLowerCase(), getDefaultProfile());
    }

    public List<ColorProfile> getAvailableProfiles() {
        return new ArrayList<>(profiles.values());
    }

    private static final class ColorProfileDescriptor {
        private final String id;
        private final String displayName;
        private final String description;
        private final String resourcePath;
        private final String outputConditionIdentifier;
        private final String outputCondition;

        private ColorProfileDescriptor(String id, String displayName, String description,
                                      String resourcePath, String outputConditionIdentifier, String outputCondition) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.resourcePath = resourcePath;
            this.outputConditionIdentifier = outputConditionIdentifier;
            this.outputCondition = outputCondition;
        }

        public static Builder builder() {
            return new Builder();
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }

        public String resourcePath() {
            return resourcePath;
        }

        public String outputConditionIdentifier() {
            return outputConditionIdentifier;
        }

        public String outputCondition() {
            return outputCondition;
        }

        public static final class Builder {
            private String id;
            private String displayName;
            private String description;
            private String resourcePath;
            private String outputConditionIdentifier;
            private String outputCondition;

            private Builder() {
            }

            public Builder id(String id) {
                this.id = id != null ? id.toLowerCase() : null;
                return this;
            }

            public Builder displayName(String displayName) {
                this.displayName = displayName;
                return this;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder resourcePath(String resourcePath) {
                this.resourcePath = resourcePath;
                return this;
            }

            public Builder outputConditionIdentifier(String outputConditionIdentifier) {
                this.outputConditionIdentifier = outputConditionIdentifier;
                return this;
            }

            public Builder outputCondition(String outputCondition) {
                this.outputCondition = outputCondition;
                return this;
            }

            public ColorProfileDescriptor build() {
                return new ColorProfileDescriptor(id, displayName, description, resourcePath,
                        outputConditionIdentifier, outputCondition);
            }
        }
    }
}
