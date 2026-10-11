package com.metallumextra.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Only patch Metallum on macOS (Metallum itself only activates there), and only patch Voxy when it is installed. */
public final class ExtraMixinPlugin implements IMixinConfigPlugin {
    private boolean isMacOs;

    @Override
    public void onLoad(final String mixinPackage) {
        this.isMacOs = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (!this.isMacOs) {
            return false;
        }
        // The Voxy bridge targets Voxy's classes, which only exist when Voxy is installed.
        return !mixinClassName.startsWith("com.metallumextra.mixin.voxy.") || FabricLoader.getInstance().isModLoaded("voxy");
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
