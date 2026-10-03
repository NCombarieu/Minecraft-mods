package com.ncombarieu.chronomancie.pouvoir;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.fx.Ephemere;
import com.ncombarieu.chronomancie.progression.Succes;
import com.ncombarieu.chronomancie.temps.Instantane;
import com.ncombarieu.chronomancie.temps.Memoire;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Miroir d'Écho : un double du joueur (avec son vrai skin) surgit là où il était il y a quelques secondes
 * et rejoue exactement ses gestes. Les monstres alentour le prennent pour cible.
 */
public final class Echo {
	private static final List<Session> ECHOS = new ArrayList<>();
	private static final DustParticleOptions LUEUR = new DustParticleOptions(Effets.CYAN, 0.9F);
	private static final int PERSISTANCE = 40;

	private Echo() {
	}

	private static final class Session {
		private final ServerPlayer proprietaire;
		private final Mannequin echo;
		private final List<Instantane> scenario;
		private int tick;
		private int coupsRecus;

		private Session(final ServerPlayer proprietaire, final Mannequin echo, final List<Instantane> scenario) {
			this.proprietaire = proprietaire;
			this.echo = echo;
			this.scenario = scenario;
		}
	}

	public static boolean demarrer(final ServerPlayer joueur) {
		ServerLevel level = joueur.level();
		List<Instantane> scenario = Memoire.derniers(joueur, ChronoConfig.get().miroir.dureeEcho * 20);
		if (scenario.size() < 20) {
			joueur.sendOverlayMessage(Component.literal("Ton passé est trop récent pour se refléter…").withStyle(ChatFormatting.GRAY));
			return false;
		}
		Mannequin echo = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (echo == null) {
			return false;
		}
		// L'étiquette « Mannequin » sous le nom n'a rien à faire là
		CompoundTag reglages = new CompoundTag();
		reglages.putBoolean("hide_description", true);
		echo.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), reglages));

		Instantane debut = scenario.getFirst();
		echo.snapTo(debut.position().x, debut.position().y, debut.position().z, debut.yRot(), debut.xRot());
		echo.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(joueur.getGameProfile()));
		echo.setCustomName(Component.literal("Écho de " + joueur.getName().getString()).withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
		echo.setCustomNameVisible(true);
		echo.setNoGravity(true);
		echo.setSilent(true);
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND }) {
			echo.setItemSlot(slot, joueur.getItemBySlot(slot).copy());
		}
		echo.setItemSlot(EquipmentSlot.MAINHAND, debut.mainDroite().copy());
		Ephemere.ajouter(level, echo);
		ECHOS.add(new Session(joueur, echo, scenario));

		Vec3 centre = echo.position().add(0, 1, 0);
		Effets.son(level, centre, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.0F, 1.0F);
		Effets.son(level, joueur.position(), SoundEvents.ILLUSIONER_PREPARE_MIRROR, 0.8F, 1.3F);
		Effets.particules(level, ParticleTypes.REVERSE_PORTAL, centre, 60, 0.4, 0.1);
		Effets.ligne(level, LUEUR, joueur.position().add(0, 1, 0), centre, 0.5);
		return true;
	}

	public static boolean estUnEcho(final Entity entity) {
		for (Session s : ECHOS) {
			if (s.echo == entity) {
				return true;
			}
		}
		return false;
	}

	/** L'écho encaisse un coup à la place de son propriétaire. Renvoie toujours false : il ne subit pas de vrais dégâts. */
	public static void encaisser(final Entity entity) {
		for (Session s : ECHOS) {
			if (s.echo == entity) {
				s.coupsRecus++;
				ServerLevel level = (ServerLevel) entity.level();
				Effets.particules(level, ParticleTypes.ENCHANTED_HIT, entity.position().add(0, 1, 0), 10, 0.3, 0.2);
				Effets.son(level, entity.position(), SoundEvents.AMETHYST_BLOCK_HIT, 1.0F, 0.8F + level.getRandom().nextFloat() * 0.4F);
				if (s.coupsRecus == 1 && !s.proprietaire.isRemoved()) {
					Succes.ECHO.accorder(s.proprietaire);
				}
				return;
			}
		}
	}

	public static void tick() {
		Iterator<Session> it = ECHOS.iterator();
		while (it.hasNext()) {
			Session s = it.next();
			Mannequin echo = s.echo;
			if (echo.isRemoved()) {
				it.remove();
				continue;
			}
			ServerLevel level = (ServerLevel) echo.level();
			s.tick++;
			if (s.tick < s.scenario.size()) {
				rejouer(s, s.scenario.get(s.tick));
			}
			echo.setDeltaMovement(Vec3.ZERO);
			if (s.tick % 2 == 0) {
				Effets.particules(level, LUEUR, echo.position().add(0, 1, 0), 2, 0.3, 0);
			}
			if (s.tick % 5 == 0) {
				attirerMonstres(s);
			}
			boolean termine = s.tick >= s.scenario.size() + PERSISTANCE
					|| s.coupsRecus >= ChronoConfig.get().miroir.coupsAvantBris
					|| s.proprietaire.isRemoved();
			if (termine) {
				briser(s);
				it.remove();
			}
		}
	}

	private static void rejouer(final Session s, final Instantane instant) {
		Mannequin echo = s.echo;
		if (instant.dimension() != echo.level().dimension()) {
			return;
		}
		echo.setPos(instant.position());
		echo.setYRot(instant.yRot());
		echo.setXRot(instant.xRot());
		echo.setYHeadRot(instant.yRot());
		echo.setYBodyRot(instant.yRot());
		Pose pose = switch (instant.pose()) {
			case CROUCHING, SWIMMING, FALL_FLYING -> instant.pose();
			default -> Pose.STANDING;
		};
		echo.setPose(pose);
		if (echo.getMainHandItem() != instant.mainDroite()) {
			echo.setItemSlot(EquipmentSlot.MAINHAND, instant.mainDroite());
		}
		if (instant.coup()) {
			echo.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		}
	}

	private static void attirerMonstres(final Session s) {
		Mannequin echo = s.echo;
		for (Mob mob : echo.level().getEntitiesOfClass(Mob.class, echo.getBoundingBox().inflate(24), m -> m instanceof Enemy && m.isAlive())) {
			if (mob.getTarget() == null || mob.getTarget() == s.proprietaire) {
				mob.setTarget(echo);
			}
		}
	}

	private static void briser(final Session s) {
		Mannequin echo = s.echo;
		ServerLevel level = (ServerLevel) echo.level();
		for (Mob mob : level.getEntitiesOfClass(Mob.class, echo.getBoundingBox().inflate(32), m -> m.getTarget() == echo)) {
			mob.setTarget(null);
		}
		Vec3 centre = echo.position().add(0, 1, 0);
		Effets.son(level, centre, SoundEvents.GLASS_BREAK, 1.0F, 0.7F);
		Effets.son(level, centre, SoundEvents.ILLUSIONER_MIRROR_MOVE, 0.7F, 0.6F);
		Effets.particules(level, LUEUR, centre, 40, 0.4, 0);
		Effets.particules(level, ParticleTypes.END_ROD, centre, 20, 0.2, 0.08);
		Ephemere.retirer(echo);
	}

	public static void toutArreter() {
		for (Session s : ECHOS) {
			Ephemere.retirer(s.echo);
		}
		ECHOS.clear();
	}
}
