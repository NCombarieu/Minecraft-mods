package com.ncombarieu.hameau;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * Le corps des villageois : une silhouette humaine (entité mannequin, connue de tout client sans mod) qui suit
 * le villageois pas à pas, tient ses outils et balance le bras. Le vrai villageois, rendu invisible, garde
 * l'intelligence de déplacement, le commerce et les points de vie.
 */
public final class Corps {
	static final String TAG = "hameau.corps";
	private static final String EQUIPE = "hameau.corps";
	private static final String[] PEAUX = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
	private static final Map<UUID, Lie> CORPS = new HashMap<>();

	private Corps() {
	}

	private static final class Lie {
		final Villager villageois;
		final Mannequin corps;
		String tenue = "";
		/** Objet tenu le temps d'un geste ; sinon l'outil du métier. */
		ItemStack enMain;
		long accroupiJusqua;

		Lie(final Villager villageois, final Mannequin corps) {
			this.villageois = villageois;
			this.corps = corps;
		}
	}

	/** Le villageois caché derrière un corps (pour rediriger clics et coups), ou null. */
	static Villager villageoisDe(final Entity entite) {
		if (entite instanceof Mannequin) {
			for (Lie lie : CORPS.values()) {
				if (lie.corps == entite) {
					return lie.villageois;
				}
			}
		}
		return null;
	}

	static Mannequin de(final Villager villageois) {
		Lie lie = CORPS.get(villageois.getUUID());
		return lie == null ? null : lie.corps;
	}

	static void tick(final MinecraftServer server) {
		long maintenant = server.getTickCount();
		boolean actif = HameauConfig.get().corps;
		if (maintenant % 20 == 0) {
			for (ServerLevel level : server.getAllLevels()) {
				for (Villager villageois : level.getEntities(EntityTypeTest.forClass(Villager.class), Entity::isAlive)) {
					boolean veutCorps = actif && (!villageois.isBaby() || HameauConfig.get().corpsEnfants);
					if (veutCorps && !CORPS.containsKey(villageois.getUUID())) {
						incarner(villageois, level);
					} else if (!veutCorps && !CORPS.containsKey(villageois.getUUID()) && Ames.connue(villageois.getUUID()) != null && villageois.hasEffect(MobEffects.INVISIBILITY)) {
						villageois.removeEffect(MobEffects.INVISIBILITY);
					}
				}
			}
		}
		Iterator<Lie> it = CORPS.values().iterator();
		while (it.hasNext()) {
			Lie lie = it.next();
			Villager v = lie.villageois;
			if (!actif || (v.isBaby() && !HameauConfig.get().corpsEnfants) || v.isRemoved() || !v.isAlive() || lie.corps.isRemoved() || lie.corps.level() != v.level()) {
				if (!lie.corps.isRemoved()) {
					if (!v.isAlive() && v.level() instanceof ServerLevel level) {
						level.sendParticles(ParticleTypes.POOF, v.getX(), v.getY() + 1, v.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
					}
					lie.corps.discard();
				}
				it.remove();
				continue;
			}
			suivre(lie, maintenant);
		}
	}

	private static void suivre(final Lie lie, final long maintenant) {
		Villager v = lie.villageois;
		Mannequin corps = lie.corps;
		corps.setPos(v.getX(), v.getY(), v.getZ());
		corps.setYRot(v.yBodyRot);
		corps.setYBodyRot(v.yBodyRot);
		corps.setYHeadRot(v.getYHeadRot());
		corps.setXRot(v.getXRot());
		corps.setDeltaMovement(Vec3.ZERO);
		Pose pose = v.isSleeping() ? Pose.SLEEPING : maintenant < lie.accroupiJusqua ? Pose.CROUCHING : Pose.STANDING;
		if (corps.getPose() != pose) {
			corps.setPose(pose);
		}
		if (maintenant % 20 == 0) {
			habiller(lie);
			if (!v.hasEffect(MobEffects.INVISIBILITY)) {
				v.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
			}
		}
	}

	private static void incarner(final Villager villageois, final ServerLevel level) {
		Mannequin corps = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
		if (corps == null) {
			return;
		}
		Ame ame = Ames.de(villageois);
		CompoundTag reglages = new CompoundTag();
		reglages.putBoolean("hide_description", true);
		reglages.putBoolean("immovable", true);
		CompoundTag profil = new CompoundTag();
		List<String> joueurs = HameauConfig.get().peauxJoueurs;
		int hasard = Math.floorMod(villageois.getUUID().hashCode(), 1000);
		if (joueurs != null && !joueurs.isEmpty()) {
			profil.putString("name", joueurs.get(hasard % joueurs.size()));
		} else {
			String modele = ame.femme ? "slim" : "wide";
			profil.putString("texture", "minecraft:entity/player/" + modele + "/" + PEAUX[hasard % PEAUX.length]);
			profil.putString("model", modele);
		}
		reglages.put("profile", profil);
		corps.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), reglages));
		corps.snapTo(villageois.getX(), villageois.getY(), villageois.getZ(), villageois.getYRot(), villageois.getXRot());
		corps.setCustomName(Component.literal(ame.nom).withStyle(ChatFormatting.YELLOW));
		corps.setCustomNameVisible(true);
		corps.setNoGravity(true);
		corps.setSilent(true);
		level.addFreshEntity(corps);
		// Posé après l'ajout au monde : au chargement, seuls les corps orphelins portent déjà ce tag.
		corps.addTag(TAG);
		// Sans collision : le corps occupe la même place que le villageois, ils ne doivent pas se repousser.
		Scoreboard tableau = level.getServer().getScoreboard();
		PlayerTeam equipe = tableau.getPlayerTeam(EQUIPE);
		if (equipe == null) {
			equipe = tableau.addPlayerTeam(EQUIPE);
			equipe.setCollisionRule(Team.CollisionRule.NEVER);
		}
		tableau.addPlayerToTeam(corps.getScoreboardName(), equipe);
		Lie lie = new Lie(villageois, corps);
		CORPS.put(villageois.getUUID(), lie);
		habiller(lie);
		villageois.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
	}

	/** Tunique aux couleurs du métier, taille d'enfant, outil en main. Recalculé quand le métier change. */
	private static void habiller(final Lie lie) {
		Villager v = lie.villageois;
		String metier = Perception.cleMetier(v);
		String tenue = metier + (v.isBaby() ? "/enfant" : "");
		if (!tenue.equals(lie.tenue)) {
			lie.tenue = tenue;
			ItemStack tunique = new ItemStack(Items.LEATHER_CHESTPLATE);
			tunique.set(DataComponents.DYED_COLOR, new DyedItemColor(couleur(metier)));
			lie.corps.setItemSlot(EquipmentSlot.CHEST, tunique);
			var taille = lie.corps.getAttribute(Attributes.SCALE);
			if (taille != null) {
				taille.setBaseValue(v.isBaby() ? 0.55 : 1.0);
			}
			lie.corps.setItemSlot(EquipmentSlot.MAINHAND, lie.enMain != null ? lie.enMain : new ItemStack(outil(metier)));
		}
	}

	private static int couleur(final String metier) {
		return switch (metier) {
			case "farmer" -> 0xC8A040;
			case "fisherman" -> 0x3A7CA5;
			case "librarian" -> 0xE8E8E8;
			case "armorer" -> 0x5A5A66;
			case "toolsmith" -> 0x2E2E36;
			case "weaponsmith" -> 0x7A1F1F;
			case "cleric" -> 0x8E3BB0;
			case "butcher" -> 0xD8C8B8;
			case "shepherd" -> 0x6B4A2B;
			case "fletcher" -> 0x3F7A3F;
			case "mason" -> 0x8A8A7A;
			case "leatherworker" -> 0x9A5B2A;
			case "cartographer" -> 0xD4B44A;
			case "nitwit" -> 0x4FB35A;
			default -> 0x7C6A55;
		};
	}

	private static Item outil(final String metier) {
		return switch (metier) {
			case "farmer" -> Items.IRON_HOE;
			case "fisherman" -> Items.FISHING_ROD;
			case "librarian" -> Items.BOOK;
			case "armorer" -> Items.SHIELD;
			case "toolsmith" -> Items.IRON_PICKAXE;
			case "weaponsmith" -> Items.IRON_SWORD;
			case "cleric" -> Items.LANTERN;
			case "butcher" -> Items.IRON_AXE;
			case "shepherd" -> Items.SHEARS;
			case "fletcher" -> Items.BOW;
			case "mason" -> Items.BRICK;
			case "leatherworker" -> Items.LEATHER;
			case "cartographer" -> Items.COMPASS;
			default -> Items.AIR;
		};
	}

	/** Met un objet dans la main du villageois le temps d'un geste. */
	static void tenir(final Villager villageois, final ItemStack objet) {
		Lie lie = CORPS.get(villageois.getUUID());
		if (lie != null) {
			lie.enMain = objet.copy();
			lie.corps.setItemSlot(EquipmentSlot.MAINHAND, lie.enMain);
		}
	}

	/** Le geste est fini : il reprend l'outil de son métier. */
	static void relacher(final Villager villageois) {
		Lie lie = CORPS.get(villageois.getUUID());
		if (lie != null && lie.enMain != null) {
			lie.enMain = null;
			lie.corps.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(outil(Perception.cleMetier(villageois))));
		}
	}

	static void balancer(final Villager villageois) {
		Lie lie = CORPS.get(villageois.getUUID());
		if (lie != null) {
			lie.corps.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		}
	}

	static void accroupir(final Villager villageois, final int ticks) {
		Lie lie = CORPS.get(villageois.getUUID());
		if (lie != null) {
			lie.accroupiJusqua = villageois.level().getServer().getTickCount() + ticks;
		}
	}

	/** Le corps accuse visiblement le coup reçu par le villageois. */
	static void accuser(final Villager villageois, final net.minecraft.world.damagesource.DamageSource source) {
		Lie lie = CORPS.get(villageois.getUUID());
		if (lie != null && villageois.level() instanceof ServerLevel level) {
			level.broadcastDamageEvent(lie.corps, source);
		}
	}

	static void toutRetirer() {
		CORPS.values().forEach(lie -> lie.corps.discard());
		CORPS.clear();
	}

	/** Un corps resté dans le monde après un arrêt brutal est supprimé au chargement. */
	static void verifier(final Entity entite) {
		if (entite instanceof Mannequin && entite.entityTags().contains(TAG) && villageoisDe(entite) == null) {
			entite.discard();
		}
	}
}
