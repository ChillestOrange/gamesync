package io.github.chillestorange.config;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.cloud.CloudStorageFactory.ProviderType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class GameSyncConfigScreen {

    private GameSyncConfigScreen() {
    }

    public static Screen createScreen(Screen parent) {
        GameSyncConfig instance = GameSyncConfig.HANDLER.instance();

        // --- General: what to sync, and when ---

        Option<String> targetWorldOption = Option.<String>createBuilder()
                .name(Component.translatable("gamesync.config.option.target_world"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.target_world.desc")))
                .binding("", () -> instance.targetWorld, v -> instance.targetWorld = v)
                .controller(StringControllerBuilder::create)
                .build();

        Option<ProviderType> cloudProviderOption = Option.<ProviderType>createBuilder()
                .name(Component.translatable("gamesync.config.option.cloud_provider"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.cloud_provider.desc")))
                .binding(
                        ProviderType.GOOGLE_DRIVE,
                        () -> {
                            try {
                                return GameSyncConfig.providerType();
                            } catch (IllegalArgumentException e) {
                                return ProviderType.GOOGLE_DRIVE;
                            }
                        },
                        v -> instance.cloudProvider = v.name()
                )
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(ProviderType.class))
                .build();

        OptionGroup syncTargetGroup = OptionGroup.createBuilder()
                .name(Component.translatable("gamesync.config.group.sync_target"))
                .option(targetWorldOption)
                .option(cloudProviderOption)
                .build();

        Option<Boolean> autosaveEnabledOption = Option.<Boolean>createBuilder()
                .name(Component.translatable("gamesync.config.option.autosave_enabled"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.autosave_enabled.desc")))
                .binding(false, () -> instance.autosaveSyncEnabled, v -> instance.autosaveSyncEnabled = v)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<Integer> autosaveIntervalOption = Option.<Integer>createBuilder()
                .name(Component.translatable("gamesync.config.option.autosave_interval"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.autosave_interval.desc")))
                .binding(6000, () -> instance.autosaveIntervalTicks, v -> instance.autosaveIntervalTicks = v)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(1200, 72000)
                        .step(1200)
                        .formatValue(v -> Component.translatable(
                                "gamesync.config.option.autosave_interval.format", v / 1200)))
                .build();

        OptionGroup autosaveGroup = OptionGroup.createBuilder()
                .name(Component.translatable("gamesync.config.group.autosave"))
                .option(autosaveEnabledOption)
                .option(autosaveIntervalOption)
                .build();

        ConfigCategory generalCategory = ConfigCategory.createBuilder()
                .name(Component.translatable("gamesync.config.category.general"))
                .group(syncTargetGroup)
                .group(autosaveGroup)
                .build();

        // --- Google Drive: where the world goes and how GameSync authenticates ---

        Option<String> remoteFolderIdOption = Option.<String>createBuilder()
                .name(Component.translatable("gamesync.config.option.remote_folder_id"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.remote_folder_id.desc")))
                .binding("", () -> instance.remoteFolderId, v -> instance.remoteFolderId = v)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> clientIdOption = Option.<String>createBuilder()
                .name(Component.translatable("gamesync.config.option.client_id"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.client_id.desc")))
                .binding("", () -> instance.clientId, v -> instance.clientId = v)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> clientSecretOption = Option.<String>createBuilder()
                .name(Component.translatable("gamesync.config.option.client_secret"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.client_secret.desc")))
                .binding("", () -> instance.clientSecret, v -> instance.clientSecret = v)
                .controller(StringControllerBuilder::create)
                .build();

        OptionGroup driveConnectionGroup = OptionGroup.createBuilder()
                .name(Component.translatable("gamesync.config.group.connection"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.group.connection.desc")))
                .option(remoteFolderIdOption)
                .option(clientIdOption)
                .option(clientSecretOption)
                .build();

        ConfigCategory driveCategory = ConfigCategory.createBuilder()
                .name(Component.translatable("gamesync.config.category.drive"))
                .group(driveConnectionGroup)
                .build();

        // --- Advanced: performance and reliability tuning ---

        Option<Integer> threadThresholdOption = Option.<Integer>createBuilder()
                .name(Component.translatable("gamesync.config.option.thread_threshold"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.thread_threshold.desc")))
                .binding(5, () -> instance.threadThreshold, v -> instance.threadThreshold = v)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 50).step(1))
                .build();

        Option<Integer> maxWorkersOption = Option.<Integer>createBuilder()
                .name(Component.translatable("gamesync.config.option.max_workers"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.max_workers.desc")))
                .binding(12, () -> instance.maxWorkers, v -> instance.maxWorkers = v)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 32).step(1))
                .build();

        OptionGroup performanceGroup = OptionGroup.createBuilder()
                .name(Component.translatable("gamesync.config.group.performance"))
                .option(threadThresholdOption)
                .option(maxWorkersOption)
                .build();

        Option<Integer> maxRetriesOption = Option.<Integer>createBuilder()
                .name(Component.translatable("gamesync.config.option.max_retries"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.max_retries.desc")))
                .binding(3, () -> instance.maxRetries, v -> instance.maxRetries = v)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 10).step(1))
                .build();

        Option<Integer> retryDelayOption = Option.<Integer>createBuilder()
                .name(Component.translatable("gamesync.config.option.retry_delay"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.retry_delay.desc")))
                .binding(1500, () -> (int) instance.retryDelay, v -> instance.retryDelay = v)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 30000).step(500))
                .build();

        OptionGroup reliabilityGroup = OptionGroup.createBuilder()
                .name(Component.translatable("gamesync.config.group.reliability"))
                .option(maxRetriesOption)
                .option(retryDelayOption)
                .build();

        Option<Boolean> debugModeOption = Option.<Boolean>createBuilder()
                .name(Component.translatable("gamesync.config.option.debug_mode"))
                .description(OptionDescription.of(Component.translatable(
                        "gamesync.config.option.debug_mode.desc")))
                .binding(false, () -> instance.debugMode, v -> {
                    instance.debugMode = v;
                    GameSyncLogger.setDebugEnabled(v);
                })
                .controller(BooleanControllerBuilder::create)
                .build();

        OptionGroup debugGroup = OptionGroup.createBuilder()
                .name(Component.translatable("gamesync.config.group.debug"))
                .option(debugModeOption)
                .build();

        ConfigCategory advancedCategory = ConfigCategory.createBuilder()
                .name(Component.translatable("gamesync.config.category.advanced"))
                .group(performanceGroup)
                .group(reliabilityGroup)
                .group(debugGroup)
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("gamesync.config.title"))
                .category(generalCategory)
                .category(driveCategory)
                .category(advancedCategory)
                .save(GameSyncConfig::save)
                .build()
                .generateScreen(parent);
    }
}
