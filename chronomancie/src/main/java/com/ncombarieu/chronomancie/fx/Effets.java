package com.ncombarieu.chronomancie.fx;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Boîte à outils visuelle et sonore. */
public final class Effets {
	public static final int VIOLET = 0xB44CFF;
	public static final int CYAN = 0x4CF0FF;
	public static final int OR = 0xFFC94C;

	private Effets() {
	}

	public static void son(final ServerLevel level, final Vec3 pos, final SoundEvent son, final float volume, final float hauteur) {
		level.playSound(null, pos.x, pos.y, pos.z, son, SoundSource.PLAYERS, volume, hauteur);
	}

	public static void son(final ServerLevel level, final Vec3 pos, final Holder<SoundEvent> son, final float volume, final float hauteur) {
		level.playSound(null, pos.x, pos.y, pos.z, son, SoundSource.PLAYERS, volume, hauteur);
	}

	public static void particules(final ServerLevel level, final ParticleOptions type, final Vec3 pos, final int nombre, final double dispersion, final double vitesse) {
		level.sendParticles(type, pos.x, pos.y, pos.z, nombre, dispersion, dispersion, dispersion, vitesse);
	}

	/** Particules visibles de très loin (jusqu'à 512 blocs) et même avec les particules réduites. */
	public static void particulesLointaines(final ServerLevel level, final ParticleOptions type, final Vec3 pos, final int nombre,
			final double dx, final double dy, final double dz, final double vitesse) {
		level.sendParticles(type, true, true, pos.x, pos.y, pos.z, nombre, dx, dy, dz, vitesse);
	}

	/** Une particule lancée dans une direction précise. */
	public static void jet(final ServerLevel level, final ParticleOptions type, final Vec3 pos, final Vec3 direction, final double vitesse) {
		level.sendParticles(type, pos.x, pos.y, pos.z, 0, direction.x, direction.y, direction.z, vitesse);
	}

	public static void jetPour(final ServerPlayer joueur, final ParticleOptions type, final Vec3 pos, final Vec3 direction, final double vitesse) {
		joueur.level().sendParticles(joueur, type, true, true, pos.x, pos.y, pos.z, 0, direction.x, direction.y, direction.z, vitesse);
	}

	/** Points répartis régulièrement sur une sphère (spirale de Fibonacci). */
	public static void sphere(final ServerLevel level, final ParticleOptions type, final Vec3 centre, final double rayon, final int points, final float decalage) {
		double angleDor = Math.PI * (3.0 - Math.sqrt(5.0));
		for (int i = 0; i < points; i++) {
			double y = 1.0 - (i + 0.5) * 2.0 / points;
			double r = Math.sqrt(1.0 - y * y);
			double theta = angleDor * i + decalage;
			Vec3 p = centre.add(Math.cos(theta) * r * rayon, y * rayon, Math.sin(theta) * r * rayon);
			level.sendParticles(type, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
	}

	public static void anneau(final ServerLevel level, final ParticleOptions type, final Vec3 centre, final double rayon, final int points, final float decalage) {
		for (int i = 0; i < points; i++) {
			double a = decalage + i * Mth.TWO_PI / points;
			level.sendParticles(type, centre.x + Math.cos(a) * rayon, centre.y, centre.z + Math.sin(a) * rayon, 1, 0, 0, 0, 0);
		}
	}

	/** Une traînée de particules entre deux points. */
	public static void ligne(final ServerLevel level, final ParticleOptions type, final Vec3 de, final Vec3 a, final double pas) {
		Vec3 delta = a.subtract(de);
		int n = Math.max(1, (int) (delta.length() / pas));
		for (int i = 0; i <= n; i++) {
			Vec3 p = de.add(delta.scale(i / (double) n));
			level.sendParticles(type, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}
	}

	// ---------- Entités d'affichage ----------

	public static Display.BlockDisplay bloc(final ServerLevel level, final Vec3 pos, final BlockState etat) {
		Display.BlockDisplay display = EntityTypes.BLOCK_DISPLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		display.setPos(pos);
		display.setBlockState(etat);
		return display;
	}

	public static Display.ItemDisplay objet(final ServerLevel level, final Vec3 pos, final ItemStack stack) {
		Display.ItemDisplay display = EntityTypes.ITEM_DISPLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		display.setPos(pos);
		display.setItemStack(stack);
		display.setItemTransform(ItemDisplayContext.FIXED);
		return display;
	}

	/**
	 * Transformation d'un bloc affiché : un bloc d'affichage a son origine dans un coin,
	 * on corrige pour qu'il tourne et rétrécisse autour de son centre.
	 */
	public static Transformation transformationCentree(final Vector3f decalage, final Quaternionf rotation, final float echelle) {
		Vector3f demi = new Vector3f(0.5F, 0.5F, 0.5F);
		Vector3f centreTourne = new Vector3f(demi).mul(echelle).rotate(rotation);
		Vector3f translation = new Vector3f(decalage).add(demi).sub(centreTourne);
		return new Transformation(translation, rotation, new Vector3f(echelle), new Quaternionf());
	}

	/** Lance une animation : la transformation sera atteinte en douceur en {@code duree} ticks. */
	public static void animer(final Display display, final Transformation cible, final int duree) {
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(duree);
		display.setTransformation(cible);
	}
}
