package com.ncombarieu.chronomancie.pouvoir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.fx.Ephemere;
import com.ncombarieu.chronomancie.monde.CicatricesExplosions;
import com.ncombarieu.chronomancie.monde.CicatricesExplosions.BlocPerdu;
import com.ncombarieu.chronomancie.monde.CicatricesExplosions.Cicatrice;
import com.ncombarieu.chronomancie.progression.Succes;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Chronoscope : les blocs soufflés par une explosion reviennent en volant reprendre leur place,
 * comme une vidéo passée à l'envers.
 */
public final class Restauration {
	private static final int BLOCS_PAR_TICK = 12;
	private static final int DUREE_VOL = 12;
	private static final int DUREE_APERCU = 100;
	private static final int APERCU_MAX = 400;

	private static final List<Chantier> CHANTIERS = new ArrayList<>();
	private static final List<Apercu> APERCUS = new ArrayList<>();

	private Restauration() {
	}

	private record EnVol(Display.BlockDisplay display, BlocPerdu bloc, int depart) {
	}

	private static final class Chantier {
		private final ServerLevel level;
		private final Deque<BlocPerdu> aFaire;
		private final List<EnVol> enVol = new ArrayList<>();
		private int tick;
		private Vec3 dernier;

		private Chantier(final ServerLevel level, final Deque<BlocPerdu> aFaire) {
			this.level = level;
			this.aFaire = aFaire;
		}
	}

	private record Apercu(UUID joueur, List<Display.BlockDisplay> fantomes, int fin) {
	}

	public static boolean utiliser(final ServerPlayer joueur, final boolean apercu) {
		ServerLevel level = joueur.level();
		ChronoConfig.Chronoscope config = ChronoConfig.get().chronoscope;
		List<Cicatrice> cicatrices = CicatricesExplosions.prendre(level, joueur.position(), config.rayon, !apercu);
		List<BlocPerdu> blocs = new ArrayList<>();
		for (Cicatrice c : cicatrices) {
			for (BlocPerdu b : c.blocs) {
				if (level.getBlockState(b.pos()).canBeReplaced()) {
					blocs.add(b);
				}
			}
		}
		if (blocs.isEmpty()) {
			joueur.sendOverlayMessage(Component.literal("Le temps est intact ici : rien à restaurer.").withStyle(ChatFormatting.GRAY));
			Effets.son(level, joueur.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 0.5F);
			return false;
		}
		Cicatrice plusRecente = cicatrices.stream().max(Comparator.comparingLong(c -> c.date)).orElseThrow();
		long secondes = (level.getGameTime() - plusRecente.date) / 20;
		Component quand = Component.literal(secondes < 60 ? "il y a " + secondes + " s" : "il y a " + secondes / 60 + " min");

		if (apercu) {
			if (APERCUS.stream().anyMatch(a -> a.joueur().equals(joueur.getUUID()))) {
				return false;
			}
			montrerApercu(joueur, blocs);
			joueur.sendSystemMessage(Component.literal("⌛ " + blocs.size() + " blocs perdus. Dernière blessure du monde : ").withStyle(ChatFormatting.GREEN)
					.append(plusRecente.cause.copy().withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(", ").withStyle(ChatFormatting.GREEN))
					.append(quand.copy().withStyle(ChatFormatting.GREEN)));
			Effets.son(level, joueur.position(), SoundEvents.SPYGLASS_USE, 1.0F, 0.8F);
			return false;
		}

		// Du bas vers le haut, les plus proches du centre d'abord
		Vec3 centre = plusRecente.centre;
		blocs.sort(Comparator.<BlocPerdu>comparingInt(b -> b.pos().getY()).thenComparingDouble(b -> Vec3.atCenterOf(b.pos()).distanceToSqr(centre)));
		if (blocs.size() > config.maxBlocsParUsage) {
			blocs = new ArrayList<>(blocs.subList(0, config.maxBlocsParUsage));
		}
		recupererDebris(level, cicatrices, blocs);
		CHANTIERS.add(new Chantier(level, new ArrayDeque<>(blocs)));
		joueur.sendSystemMessage(Component.literal("⌛ Le monde se souvient… ").withStyle(ChatFormatting.GREEN)
				.append(plusRecente.cause.copy().withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(", " + quand.getString() + " : " + blocs.size() + " blocs reprennent leur place.").withStyle(ChatFormatting.GREEN)));
		Effets.son(level, joueur.position(), SoundEvents.BEACON_POWER_SELECT, 1.0F, 0.6F);
		if (blocs.size() >= 100) {
			Succes.RESTAURATION.accorder(joueur);
		}
		return true;
	}

	/**
	 * Les objets lâchés par l'explosion qui traînent encore au sol sont « repris » par le passé :
	 * sinon restaurer un mur soufflé dupliquerait ses blocs.
	 */
	private static void recupererDebris(final ServerLevel level, final List<Cicatrice> cicatrices, final List<BlocPerdu> blocs) {
		Map<Item, Integer> aReprendre = new HashMap<>();
		for (BlocPerdu b : blocs) {
			Item item = b.etat().getBlock().asItem();
			if (item != Items.AIR) {
				aReprendre.merge(item, 1, Integer::sum);
			}
		}
		for (Cicatrice c : cicatrices) {
			AABB zone = AABB.ofSize(c.centre, 24, 24, 24);
			for (ItemEntity debris : level.getEntitiesOfClass(ItemEntity.class, zone, ItemEntity::isAlive)) {
				ItemStack stack = debris.getItem();
				Integer reste = aReprendre.get(stack.getItem());
				if (reste == null || reste <= 0) {
					continue;
				}
				int pris = Math.min(reste, stack.getCount());
				aReprendre.put(stack.getItem(), reste - pris);
				Effets.particules(level, ParticleTypes.REVERSE_PORTAL, debris.position(), 5, 0.1, 0.05);
				stack.shrink(pris);
				if (stack.isEmpty()) {
					debris.discard();
				} else {
					debris.setItem(stack);
				}
			}
		}
	}

	private static void montrerApercu(final ServerPlayer joueur, final List<BlocPerdu> blocs) {
		ServerLevel level = joueur.level();
		List<Display.BlockDisplay> fantomes = new ArrayList<>();
		for (BlocPerdu b : blocs) {
			if (fantomes.size() >= APERCU_MAX) {
				break;
			}
			Display.BlockDisplay fantome = Effets.bloc(level, Vec3.atLowerCornerOf(b.pos()), b.etat());
			fantome.setTransformation(Effets.transformationCentree(new Vector3f(), new Quaternionf(), 0.6F));
			fantome.setGlowingTag(true);
			fantome.setGlowColorOverride(Effets.CYAN);
			fantome.setViewRange(2.0F);
			fantomes.add(Ephemere.ajouter(level, fantome));
		}
		APERCUS.add(new Apercu(joueur.getUUID(), fantomes, level.getServer().getTickCount() + DUREE_APERCU));
	}

	public static void tick(final int tickServeur) {
		Iterator<Apercu> ia = APERCUS.iterator();
		while (ia.hasNext()) {
			Apercu a = ia.next();
			if (tickServeur >= a.fin()) {
				a.fantomes().forEach(Ephemere::retirer);
				ia.remove();
			}
		}

		Iterator<Chantier> it = CHANTIERS.iterator();
		while (it.hasNext()) {
			Chantier c = it.next();
			c.tick++;
			lancer(c);
			atterrir(c);
			if (c.aFaire.isEmpty() && c.enVol.isEmpty()) {
				if (c.dernier != null) {
					// Un dernier accord pour signer la réparation
					Effets.son(c.level, c.dernier, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5F, 1.0F);
					Effets.son(c.level, c.dernier, SoundEvents.BELL_RESONATE, 0.6F, 1.5F);
					Effets.particules(c.level, ParticleTypes.END_ROD, c.dernier, 25, 1.5, 0.05);
				}
				it.remove();
			}
		}
	}

	private static void lancer(final Chantier c) {
		RandomSource random = c.level.getRandom();
		for (int i = 0; i < BLOCS_PAR_TICK && !c.aFaire.isEmpty(); i++) {
			BlocPerdu b = c.aFaire.pollFirst();
			Display.BlockDisplay piece = Effets.bloc(c.level, Vec3.atLowerCornerOf(b.pos()), b.etat());
			// Le bloc part d'un point éparpillé, tout petit et de travers…
			Vector3f eparpille = new Vector3f((random.nextFloat() - 0.5F) * 7, 1.5F + random.nextFloat() * 4, (random.nextFloat() - 0.5F) * 7);
			Quaternionf travers = new Quaternionf().rotateXYZ(random.nextFloat() * 6.28F, random.nextFloat() * 6.28F, random.nextFloat() * 6.28F);
			piece.setTransformation(Effets.transformationCentree(eparpille, travers, 0.2F));
			piece.setViewRange(1.5F);
			Ephemere.ajouter(c.level, piece);
			c.enVol.add(new EnVol(piece, b, c.tick));
		}
	}

	private static void atterrir(final Chantier c) {
		ServerLevel level = c.level;
		Iterator<EnVol> it = c.enVol.iterator();
		while (it.hasNext()) {
			EnVol v = it.next();
			int age = c.tick - v.depart();
			if (age == 2) {
				// …puis glisse vers sa place (l'animation est interpolée par le client)
				Effets.animer(v.display(), Effets.transformationCentree(new Vector3f(), new Quaternionf(), 1.0F), DUREE_VOL);
			} else if (age >= DUREE_VOL + 2) {
				BlockPos pos = v.bloc().pos();
				if (level.getBlockState(pos).canBeReplaced()) {
					level.setBlock(pos, v.bloc().etat(), Block.UPDATE_ALL);
					c.dernier = Vec3.atCenterOf(pos);
					if (level.getRandom().nextInt(4) == 0) {
						level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, v.bloc().etat()), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
						Effets.son(level, Vec3.atCenterOf(pos), SoundEvents.AMETHYST_BLOCK_CHIME, 0.4F, 0.8F + level.getRandom().nextFloat() * 0.8F);
					}
				}
				Ephemere.retirer(v.display());
				it.remove();
			}
		}
	}

	public static void toutArreter() {
		for (Chantier c : CHANTIERS) {
			// On termine les chantiers d'un coup plutôt que de perdre les blocs
			for (EnVol v : c.enVol) {
				if (c.level.getBlockState(v.bloc().pos()).canBeReplaced()) {
					c.level.setBlock(v.bloc().pos(), v.bloc().etat(), Block.UPDATE_ALL);
				}
				Ephemere.retirer(v.display());
			}
			for (BlocPerdu b : c.aFaire) {
				if (c.level.getBlockState(b.pos()).canBeReplaced()) {
					c.level.setBlock(b.pos(), b.etat(), Block.UPDATE_ALL);
				}
			}
		}
		CHANTIERS.clear();
		for (Apercu a : APERCUS) {
			a.fantomes().forEach(Ephemere::retirer);
		}
		APERCUS.clear();
	}
}
