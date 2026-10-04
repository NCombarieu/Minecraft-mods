package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.TagValueInput;

/** Ce qu'on voit et entend d'un villageois : bulle de texte au-dessus de sa tête, particules d'émotion, voix. */
public final class Bulles {
	static final String TAG = "hameau.bulle";
	private static final Map<UUID, Bulle> BULLES = new HashMap<>();

	private Bulles() {
	}

	private record Bulle(Display.TextDisplay affichage, long fin) {
	}

	private static ListTag flottants(final float... valeurs) {
		ListTag liste = new ListTag();
		for (float valeur : valeurs) {
			liste.add(FloatTag.valueOf(valeur));
		}
		return liste;
	}

	/** Affiche un texte au-dessus du villageois pendant quelques secondes (remplace la bulle précédente). */
	static void montrer(final Villager villageois, final String texte, final boolean discret, final long maintenant) {
		effacer(villageois.getUUID());
		ServerLevel level = (ServerLevel) villageois.level();
		Display.TextDisplay affichage = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
		if (affichage == null) {
			return;
		}
		CompoundTag reglages = new CompoundTag();
		reglages.putString("text", texte);
		reglages.putString("billboard", "center");
		reglages.putInt("line_width", 170);
		reglages.putInt("background", discret ? 0x40000000 : 0xA0101018);
		reglages.putFloat("view_range", 0.5F);
		reglages.putByte("text_opacity", (byte) (discret ? 170 : 255));
		CompoundTag transformation = new CompoundTag();
		transformation.put("translation", flottants(0F, 0.8F, 0F));
		transformation.put("left_rotation", flottants(0F, 0F, 0F, 1F));
		transformation.put("right_rotation", flottants(0F, 0F, 0F, 1F));
		float taille = discret ? 0.75F : 0.9F;
		transformation.put("scale", flottants(taille, taille, taille));
		reglages.put("transformation", transformation);
		affichage.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), reglages));
		affichage.setPos(villageois.getX(), villageois.getY() + villageois.getBbHeight(), villageois.getZ());
		level.addFreshEntity(affichage);
		// Posé après l'ajout au monde : au chargement, seules les bulles orphelines portent déjà ce tag.
		affichage.addTag(TAG);
		affichage.startRiding(villageois, true, false);
		int duree = Math.clamp(50 + texte.length() * 2L, 60, 200);
		BULLES.put(villageois.getUUID(), new Bulle(affichage, maintenant + duree));
	}

	static void effacer(final UUID villageois) {
		Bulle bulle = BULLES.remove(villageois);
		if (bulle != null) {
			bulle.affichage().discard();
		}
	}

	static void tick(final long maintenant) {
		if (maintenant % 5 != 0 || BULLES.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Bulle>> it = BULLES.entrySet().iterator();
		while (it.hasNext()) {
			Bulle bulle = it.next().getValue();
			if (maintenant >= bulle.fin() || !bulle.affichage().isAlive()) {
				bulle.affichage().discard();
				it.remove();
			}
		}
	}

	static void toutEffacer() {
		List<Bulle> bulles = new ArrayList<>(BULLES.values());
		BULLES.clear();
		bulles.forEach(b -> b.affichage().discard());
	}

	/** Une bulle restée dans le monde après un arrêt brutal est supprimée au chargement. */
	static void verifier(final Entity entite) {
		if (entite instanceof Display.TextDisplay && entite.entityTags().contains(TAG)) {
			for (Bulle bulle : BULLES.values()) {
				if (bulle.affichage() == entite) {
					return;
				}
			}
			entite.discard();
		}
	}

	/** Particules et son selon l'émotion : on lit l'humeur d'un villageois de loin. */
	static void emouvoir(final Villager villageois, final Ame ame, final String emotion, final boolean parle) {
		ServerLevel level = (ServerLevel) villageois.level();
		String e = emotion == null ? "neutre" : emotion.toLowerCase();
		ParticleOptions particule = switch (e) {
			case "joie" -> ParticleTypes.HAPPY_VILLAGER;
			case "colere", "colère" -> ParticleTypes.ANGRY_VILLAGER;
			case "amour" -> ParticleTypes.HEART;
			case "tristesse" -> ParticleTypes.FALLING_WATER;
			case "rire" -> ParticleTypes.NOTE;
			case "peur" -> ParticleTypes.SPLASH;
			case "surprise" -> ParticleTypes.ENCHANT;
			default -> null;
		};
		if (particule != null) {
			int combien = e.startsWith("col") || e.equals("amour") ? 3 : 7;
			level.sendParticles(particule, villageois.getX(), villageois.getY() + villageois.getBbHeight() + 0.3, villageois.getZ(), combien, 0.3, 0.2, 0.3, 0.02);
		}
		SoundEvent son = switch (e) {
			case "joie", "rire", "amour" -> SoundEvents.VILLAGER_YES;
			case "colere", "colère" -> SoundEvents.VILLAGER_NO;
			case "surprise" -> SoundEvents.VILLAGER_TRADE;
			default -> parle ? SoundEvents.VILLAGER_AMBIENT : null;
		};
		if (son != null) {
			float voix = ame.voix == 0 ? 1F : ame.voix;
			level.playSound(null, villageois.getX(), villageois.getY(), villageois.getZ(), son, SoundSource.NEUTRAL, 1F, ame.enfant ? voix + 0.4F : voix);
		}
	}
}
