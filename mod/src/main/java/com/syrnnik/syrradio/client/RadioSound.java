package com.syrnnik.syrradio.client;

import com.mojang.blaze3d.audio.OggAudioStream;
import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.config.ClientConfig;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Трек радио как звук игры. Звукового файла в ресурсах нет: поток отдаём
 * сами из присланных байтов, а звуковой движок играет его как музыкальный
 * стрим — без позиции в мире, одинаково слышно везде.
 */
final class RadioSound extends AbstractTickableSoundInstance {
    private static final ResourceLocation ID = new ResourceLocation(SyrRadio.MOD_ID, "radio");

    private final byte[] data;
    private final long offsetMs;

    RadioSound(byte[] data, long offsetMs) {
        // MASTER: на громкость влияет только общий ползунок игры и свой ползунок радио,
        // а не «Музыка» — её можно выключить и слушать радио, и наоборот.
        super(SoundEvent.createVariableRangeEvent(ID), SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.data = data;
        this.offsetMs = offsetMs;
        this.looping = false;
        this.delay = 0;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
        this.volume = ClientConfig.VOLUME.get().floatValue();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(ID.toString(), ConstantFloat.of(1f), ConstantFloat.of(1f), 1,
            Sound.Type.FILE, true, false, 16);
        return new WeighedSoundEvents(ID, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary lib, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AudioStream ogg = new OggAudioStream(new ByteArrayInputStream(data));
                return offsetMs > 0 ? new SkippingAudioStream(ogg, offsetMs) : ogg;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public boolean canStartSilent() {
        // Иначе при громкости 0 движок не запустит звук, и потом его будет не прибавить.
        return true;
    }

    @Override
    public void tick() {
        this.volume = ClientConfig.VOLUME.get().floatValue();
    }

    void halt() {
        stop();
    }
}
