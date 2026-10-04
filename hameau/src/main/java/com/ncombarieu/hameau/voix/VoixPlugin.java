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
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/** Le pont avec Simple Voice Chat : chargé seulement si ce mod est présent (point d'entrée « voicechat »). */
public final class VoixPlugin implements VoicechatPlugin, Voix.Diffuseur {
	private static final String CATEGORIE = "hameau";
	private final Map<UUID, AudioPlayer> enCours = new ConcurrentHashMap<>();
	private final Map<UUID, Ecoute> ecoutes = new ConcurrentHashMap<>();
	private volatile VoicechatServerApi api;

	/** La prise de parole en cours d'un joueur, décodée au fil des paquets du micro. */
	private static final class Ecoute {
		static final int MAX = 48000 * 15;
		final OpusDecoder decodeur;
		short[] tampon = new short[48000 * 4];
		int taille;
		long dernier;

		Ecoute(final OpusDecoder decodeur) {
			this.decodeur = decodeur;
		}
	}

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
			ecoutes.clear();
		});
		registre.registerEvent(MicrophonePacketEvent.class, this::micro);
	}

	private void micro(final MicrophonePacketEvent evenement) {
		VoicechatServerApi a = api;
		VoicechatConnection connexion = evenement.getSenderConnection();
		if (a == null || connexion == null || !Voix.ecoute()) {
			return;
		}
		UUID joueur = connexion.getPlayer().getUuid();
		byte[] paquet = evenement.getPacket().getOpusEncodedData();
		Ecoute ecoute = ecoutes.computeIfAbsent(joueur, uuid -> new Ecoute(a.createDecoder()));
		synchronized (ecoute) {
			// Un paquet vide marque la fin de la prise de parole (touche relâchée).
			if (paquet.length == 0) {
				finir(joueur, ecoute);
				return;
			}
			short[] son = ecoute.decodeur.decode(paquet);
			if (ecoute.taille + son.length > ecoute.tampon.length) {
				if (ecoute.tampon.length >= Ecoute.MAX) {
					return;
				}
				ecoute.tampon = java.util.Arrays.copyOf(ecoute.tampon, Math.min(Ecoute.MAX, ecoute.tampon.length * 2));
			}
			int copies = Math.min(son.length, ecoute.tampon.length - ecoute.taille);
			System.arraycopy(son, 0, ecoute.tampon, ecoute.taille, copies);
			ecoute.taille += copies;
			ecoute.dernier = System.currentTimeMillis();
		}
	}

	/** À appeler en tenant le verrou de l'écoute. */
	private static void finir(final UUID joueur, final Ecoute ecoute) {
		int taille = ecoute.taille;
		ecoute.taille = 0;
		ecoute.decodeur.resetState();
		// Moins d'une demi-seconde : un bruit, pas une phrase.
		if (taille >= 24000) {
			Voix.entendu(joueur, java.util.Arrays.copyOf(ecoute.tampon, taille));
		}
	}

	@Override
	public void tick() {
		long maintenant = System.currentTimeMillis();
		ecoutes.forEach((joueur, ecoute) -> {
			synchronized (ecoute) {
				if (ecoute.taille > 0 && maintenant - ecoute.dernier > 600) {
					finir(joueur, ecoute);
				}
			}
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
