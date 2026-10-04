package com.ncombarieu.hameau.voix;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ncombarieu.hameau.Hameau;
import com.ncombarieu.hameau.Voix;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/** Le pont avec Simple Voice Chat : chargé seulement si ce mod est présent (point d'entrée « voicechat »). */
public final class VoixPlugin implements VoicechatPlugin, Voix.Diffuseur {
	private static final String CATEGORIE = "hameau";
	private final Map<UUID, AudioPlayer> enCours = new ConcurrentHashMap<>();
	private volatile VoicechatServerApi api;

	@Override
	public String getPluginId() {
		return Hameau.MOD_ID;
	}

	@Override
	public void registerEvents(final EventRegistration registre) {
		registre.registerEvent(VoicechatServerStartedEvent.class, evenement -> {
			api = evenement.getVoicechat();
			// Un curseur de volume à part dans les réglages de Simple Voice Chat.
			api.registerVolumeCategory(api.volumeCategoryBuilder().setId(CATEGORIE).setName("Villageois").setDescription("La voix des villageois de Hameau").build());
			Voix.diffuseur = this;
			Hameau.LOGGER.info("Hameau : Simple Voice Chat détecté, les villageois peuvent parler à voix haute.");
		});
		registre.registerEvent(VoicechatServerStoppedEvent.class, evenement -> {
			Voix.diffuseur = null;
			enCours.clear();
		});
	}

	@Override
	public boolean aDesAuditeurs(final Villager villageois, final UUID seul, final float distance) {
		VoicechatServerApi a = api;
		if (a == null) {
			return false;
		}
		for (ServerPlayer joueur : ((ServerLevel) villageois.level()).players()) {
			if ((seul == null || seul.equals(joueur.getUUID())) && joueur.distanceTo(villageois) <= distance) {
				VoicechatConnection connexion = a.getConnectionOf(joueur.getUUID());
				if (connexion != null && connexion.isInstalled() && !connexion.isDisabled()) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void jouer(final Villager villageois, final short[] son, final UUID seul, final float distance) {
		VoicechatServerApi a = api;
		if (a == null) {
			return;
		}
		EntityAudioChannel canal = a.createEntityAudioChannel(UUID.randomUUID(), a.fromEntity(villageois));
		if (canal == null) {
			return;
		}
		canal.setCategory(CATEGORIE);
		canal.setDistance(distance);
		if (seul != null) {
			canal.setFilter(joueur -> seul.equals(joueur.getUuid()));
		}
		// Une nouvelle réplique coupe la précédente : un villageois n'a qu'une bouche.
		AudioPlayer precedent = enCours.remove(villageois.getUUID());
		if (precedent != null) {
			precedent.stopPlaying();
		}
		AudioPlayer lecteur = a.createAudioPlayer(canal, a.createEncoder(), son);
		enCours.put(villageois.getUUID(), lecteur);
		lecteur.setOnStopped(() -> enCours.remove(villageois.getUUID(), lecteur));
		lecteur.startPlaying();
	}
}
