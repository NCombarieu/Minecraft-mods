package com.ncombarieu.chronomancie.pouvoir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.progression.Succes;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Montre de Stase : une bulle où le temps s'arrête. Flèches suspendues en plein vol, monstres pétrifiés,
 * TNT dont la mèche ne brûle plus… Les coups portés aux créatures figées s'accumulent et tombent d'un seul
 * coup quand le temps reprend.
 */
public final class Stase {
	/** Tags posés sur les entités figées, pour les libérer même après un arrêt brutal du serveur. */
	public static final String TAG_GRAVITE = "chronomancie.fige.gravite";
	public static final String TAG_IA = "chronomancie.fige.ia";

	private static final List<Bulle> BULLES = new ArrayList<>();
	/** Quelle bulle retient quelle entité (une entité n'est figée que par une bulle à la fois). */
	private static final Map<UUID, Bulle> FIGEES = new HashMap<>();
	private static final DustParticleOptions GIVRE = new DustParticleOptions(0x9FD8FF, 1.1F);
	private static final DustParticleOptions BLEU = new DustParticleOptions(0x3F6BFF, 0.8F);

	private Stase() {
	}

	private static final class Figee {
		private final Entity entity;
		private final Vec3 position;
		private final Vec3 vitesse;
		private final double acceleration;
		private float degats;
		private DamageSource source;

		private Figee(final Entity entity) {
			this.entity = entity;
			this.position = entity.position();
			this.vitesse = entity.getDeltaMovement();
			this.acceleration = entity instanceof AbstractHurtingProjectile p ? p.accelerationPower : 0;
		}
	}

	private static final class Bulle {
		private final ServerLevel level;
		private final ServerPlayer lanceur;
		private final Vec3 centre;
		private final double rayon;
		private final int duree;
		private int tick;
		private int record;
		private final Map<UUID, Figee> figees = new HashMap<>();

		private Bulle(final ServerPlayer lanceur, final double rayon, final int duree) {
			this.level = lanceur.level();
			this.lanceur = lanceur;
			this.centre = lanceur.position().add(0, 1, 0);
			this.rayon = rayon;
			this.duree = duree;
		}
	}

	public static boolean demarrer(final ServerPlayer joueur) {
		ChronoConfig.Stase config = ChronoConfig.get().stase;
		Bulle bulle = new Bulle(joueur, config.rayon, Math.max(1, config.duree) * 20);
		BULLES.add(bulle);
		ServerLevel level = bulle.level;
		Effets.son(level, bulle.centre, SoundEvents.BEACON_ACTIVATE, 1.5F, 0.5F);
		Effets.son(level, bulle.centre, SoundEvents.BELL_RESONATE, 1.0F, 0.5F);
		Effets.particules(level, ColorParticleOption.create(ParticleTypes.FLASH, 0xFF9FD8FF), bulle.centre, 1, 0, 0);
		figer(bulle);
		return true;
	}

	private static boolean figeable(final Entity e) {
		if (e instanceof Mob mob) {
			return !mob.isNoAi();
		}
		return e instanceof Projectile || e instanceof PrimedTnt || e instanceof FallingBlockEntity
				|| e instanceof ItemEntity || e instanceof ExperienceOrb;
	}

	private static void figer(final Bulle bulle) {
		AABB zone = AABB.ofSize(bulle.centre, bulle.rayon * 2, bulle.rayon * 2, bulle.rayon * 2);
		double r2 = bulle.rayon * bulle.rayon;
		for (Entity e : bulle.level.getEntities((Entity) null, zone, e -> e.isAlive() && e != bulle.lanceur)) {
			if (e.position().distanceToSqr(bulle.centre) > r2) {
				continue;
			}
			if (e instanceof ServerPlayer autre) {
				if (!autre.isCreative() && !autre.isSpectator()) {
					autre.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 10, 6, false, false, true));
					autre.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 10, 4, false, false, true));
					autre.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 10, 250, false, false, false));
				}
				continue;
			}
			if (!figeable(e) || FIGEES.containsKey(e.getUUID())) {
				continue;
			}
			Figee f = new Figee(e);
			if (!e.isNoGravity()) {
				e.setNoGravity(true);
				e.addTag(TAG_GRAVITE);
			}
			if (e instanceof Mob mob) {
				mob.setNoAi(true);
				mob.addTag(TAG_IA);
			}
			if (e instanceof AbstractHurtingProjectile p) {
				p.accelerationPower = 0;
			}
			e.setDeltaMovement(Vec3.ZERO);
			bulle.figees.put(e.getUUID(), f);
			FIGEES.put(e.getUUID(), bulle);
			Effets.particules(bulle.level, GIVRE, e.position().add(0, e.getBbHeight() / 2, 0), 6, 0.3, 0);
		}
		bulle.record = Math.max(bulle.record, bulle.figees.size());
		if (bulle.record >= 10) {
			Succes.STASE.accorder(bulle.lanceur);
		}
	}

	/** Un coup porté à une créature figée : on le met de côté. Renvoie false si les dégâts doivent être annulés. */
	public static boolean autoriserDegats(final LivingEntity cible, final DamageSource source, final float montant) {
		Bulle bulle = FIGEES.get(cible.getUUID());
		if (bulle == null || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return true;
		}
		Figee f = bulle.figees.get(cible.getUUID());
		if (f == null) {
			return true;
		}
		f.degats += montant;
		f.source = source;
		ServerLevel level = bulle.level;
		Effets.particules(level, ParticleTypes.CRIT, cible.position().add(0, cible.getBbHeight() * 0.6, 0), 8, 0.2, 0.3);
		Effets.son(level, cible.position(), SoundEvents.AMETHYST_BLOCK_HIT, 0.8F, 1.6F);
		return false;
	}

	public static void tick() {
		Iterator<Bulle> it = BULLES.iterator();
		while (it.hasNext()) {
			Bulle b = it.next();
			b.tick++;
			// Chaque tick : une flèche file à 3 blocs par tick, elle ne doit pas traverser la bulle
			figer(b);
			maintenir(b);
			dessiner(b);
			if (b.tick >= b.duree || b.lanceur.isRemoved()) {
				liberer(b);
				it.remove();
			}
		}
	}

	private static void maintenir(final Bulle b) {
		Iterator<Figee> it = b.figees.values().iterator();
		while (it.hasNext()) {
			Figee f = it.next();
			Entity e = f.entity;
			if (e.isRemoved()) {
				FIGEES.remove(e.getUUID());
				it.remove();
				continue;
			}
			if (e.position().distanceToSqr(f.position) > 1.0E-4) {
				e.setPos(f.position);
			}
			e.setDeltaMovement(Vec3.ZERO);
			if (e instanceof PrimedTnt tnt) {
				tnt.setFuse(tnt.getFuse() + 1);
			}
		}
	}

	private static void dessiner(final Bulle b) {
		ServerLevel level = b.level;
		int restant = b.duree - b.tick;
		// Onde de choc à l'ouverture, puis paroi de la bulle
		if (b.tick <= 8) {
			Effets.anneau(level, GIVRE, b.centre, b.rayon * b.tick / 8.0, 48, 0);
		}
		if (b.tick % 3 == 0) {
			Effets.sphere(level, b.tick % 6 == 0 ? GIVRE : BLEU, b.centre, b.rayon, 90, b.tick * 0.05F);
		}
		if (b.tick % 10 == 0) {
			// Tic… tac…
			Effets.son(level, b.centre, SoundEvents.NOTE_BLOCK_HAT, 1.0F, (b.tick / 10) % 2 == 0 ? 1.2F : 0.8F);
			for (Figee f : b.figees.values()) {
				Effets.particules(level, ParticleTypes.ENCHANT, f.entity.position().add(0, f.entity.getBbHeight() / 2, 0), 4, 0.3, 0.5);
			}
		}
		if (!b.lanceur.isRemoved()) {
			int barres = 20 * restant / b.duree;
			b.lanceur.sendOverlayMessage(Component.literal("⏸ Stase  ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal("|".repeat(barres)).withStyle(ChatFormatting.BLUE))
					.append(Component.literal("|".repeat(20 - barres)).withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(String.format("  %.1f s", restant / 20.0)).withStyle(ChatFormatting.GRAY)));
		}
	}

	private static void liberer(final Bulle b) {
		ServerLevel level = b.level;
		for (Figee f : b.figees.values()) {
			Entity e = f.entity;
			FIGEES.remove(e.getUUID());
			if (e.isRemoved()) {
				continue;
			}
			degeler(e);
			if (e instanceof AbstractHurtingProjectile p) {
				p.accelerationPower = f.acceleration;
			}
			e.setDeltaMovement(f.vitesse);
			e.needsSync = true;
			if (f.degats > 0 && e instanceof LivingEntity vivant) {
				vivant.setInvulnerableTime(0);
				vivant.hurtServer(level, f.source, f.degats);
				Effets.particules(level, ParticleTypes.CRIT, e.position().add(0, e.getBbHeight() / 2, 0), 25, 0.3, 0.5);
				Effets.son(level, e.position(), SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.7F);
			}
		}
		b.figees.clear();
		Effets.son(level, b.centre, SoundEvents.BEACON_DEACTIVATE, 1.5F, 0.7F);
		Effets.son(level, b.centre, SoundEvents.BREEZE_SHOOT, 1.0F, 0.6F);
		Effets.sphere(level, ParticleTypes.END_ROD, b.centre, b.rayon * 0.5, 60, 0);
		if (!b.lanceur.isRemoved()) {
			b.lanceur.sendOverlayMessage(Component.literal("▶ Le temps reprend son cours").withStyle(ChatFormatting.AQUA));
		}
	}

	private static void degeler(final Entity e) {
		if (e.entityTags().contains(TAG_GRAVITE)) {
			e.setNoGravity(false);
			e.removeTag(TAG_GRAVITE);
		}
		if (e instanceof Mob mob && mob.entityTags().contains(TAG_IA)) {
			mob.setNoAi(false);
			mob.removeTag(TAG_IA);
		}
	}

	/** Au chargement : une entité restée figée après un arrêt brutal est libérée. */
	public static void verifier(final Entity e) {
		if (!FIGEES.containsKey(e.getUUID())) {
			degeler(e);
		}
	}

	public static void toutLiberer() {
		for (Bulle b : BULLES) {
			b.tick = b.duree;
			liberer(b);
		}
		BULLES.clear();
		FIGEES.clear();
	}
}
