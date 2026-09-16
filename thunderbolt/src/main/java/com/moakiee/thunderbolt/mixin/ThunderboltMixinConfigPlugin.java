package com.moakiee.thunderbolt.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;

/** Applies optional-addon mixins only when their owning mod is present. */
public final class ThunderboltMixinConfigPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return OptionalMixinSelector.shouldApply(mixinClassName, ThunderboltMixinConfigPlugin::isModLoaded);
    }

    private static boolean isModLoaded(String modId) {
        try {
            var loadingMods = LoadingModList.get();
            return loadingMods != null && loadingMods.getModFileById(modId) != null;
        } catch (RuntimeException ignored) {
            // Do not hide a real target/injection failure if a non-standard loader invokes this plugin
            // before it has made the loading mod list available.
            return true;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
