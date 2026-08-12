package com.zhenzi233.timebus.mixin;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 早期 mixin 注册（最小 coremod 壳）。
 *
 * <p>时间减速总线需要拦截 {@code World.updateEntities} 的 ITickable.update 调用
 * 点——World 是 MC 核心类，在 MOD 阶段（{@code @env(MOD)} 的
 * timebus.mod.mixin.json）应用 mixin 时早已被加载（MixinTargetAlreadyLoadedException，
 * 表现为 Re-entrance 崩溃）。本类以 IFMLLoadingPlugin（coremod）身份在启动早期被
 * FML 实例化，MixinBooter 识别到 IEarlyMixinLoader 后把
 * {@code timebus.early.mixin.json} 注册进 mixin 处理器，World 首次加载时即可应用
 * mixin。
 *
 * <p>dev 环境不读 jar manifest，由 build.gradle 的 {@code crl.dev.mixin} 系统属性
 * 直接早期注入同一份 json。本类只持有字符串常量，构造函数与各方法不引用任何
 * MC 运行时类（coremod 在 launch 早期实例化）。
 */
public class TimeBusEarlyMixinLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("timebus.early.mixin.json");
    }

    @Nullable
    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Nullable
    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
