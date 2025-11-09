package com.figma.export.color;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.util.Objects;

public final class ColorProfile {

    private final String id;
    private final String displayName;
    private final String description;
    private final String outputConditionIdentifier;
    private final String outputCondition;
    private final ICC_Profile iccProfile;
    private final ICC_ColorSpace colorSpace;
    private final byte[] iccBytes;

    public ColorProfile(String id,
                        String displayName,
                        String description,
                        String outputConditionIdentifier,
                        String outputCondition,
                        ICC_Profile iccProfile,
                        ICC_ColorSpace colorSpace,
                        byte[] iccBytes) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.description = description;
        this.outputConditionIdentifier = outputConditionIdentifier;
        this.outputCondition = outputCondition;
        this.iccProfile = Objects.requireNonNull(iccProfile, "iccProfile");
        this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        this.iccBytes = Objects.requireNonNull(iccBytes, "iccBytes").clone();
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getOutputConditionIdentifier() {
        return outputConditionIdentifier;
    }

    public String getOutputCondition() {
        return outputCondition;
    }

    public ICC_Profile getIccProfile() {
        return iccProfile;
    }

    public ICC_ColorSpace getColorSpace() {
        return colorSpace;
    }

    public byte[] getIccBytes() {
        return iccBytes.clone();
    }
}
