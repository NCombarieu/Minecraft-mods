package com.ncombarieu.chronomancie.faille;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;
import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.fx.Effets;
import com.ncombarieu.chronomancie.fx.Ephemere;
import com.ncombarieu.chronomancie.item.Artefact;
import com.ncombarieu.chronomancie.item.Artefacts;
import com.ncombarieu.chronomancie.progression.Succes;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Une faille temporelle : un tourbillon d'éclats de l'époque d'où elle vient, autour d'un sablier géant.
 * <p>
 * Dormante, elle attend. Quand un joueur s'en approche, elle s'éveille et recrache des vagues de
 * « vestiges » de son époque. Une fois la dernière vague vaincue, elle s'effondre et libère ses fragments.
 */
public final class Faille {
	public enum Etat { DORMANTE, EVEILLEE, ENTRE_VAGUES, EFFONDREMENT, TERMINEE }

	private static final int ECLATS = 10;
	private static final double RAYON_ORBITE = 1.8;
	private static final int PAS_ORBITE = 4;
	private static final double DISTANCE_EVEIL = 6.0;
	private static final double DISTANCE_ABANDON = 48.0;
	private static final int DUREE_EFFONDREMENT = 50;
	private static final int PAUSE_ENTRE_VAGUES = 60;

	private final ServerLevel level;
	private final Vec3 centre;
	private final Epoque epoque;
	private final DustParticleOptions poussiere;

	private Etat etat = Etat.DORMANTE;
	private int age;
	private int tickEtat;
	private int vague;
	private int ticksSansPersonne;

	private Display.ItemDisplay coeur;
	private Display.TextDisplay texte;
	private final List<Display.BlockDisplay> eclats = new ArrayList<>();
	private final List<Mob> vestiges = new ArrayList<>();
	private final Set<UUID> temoins = new HashSet<>();
	private final Set<UUID> combattants = new HashSet<>();

	public Faille(final ServerLevel level, final Vec3 centre, final Epoque epoque) {
		this.level = level;
		this.centre = centre;
		this.epoque = epoque;
		this.poussiere = new DustParticleOptions(epoque.rgb, 1.4F);
	}

	public ServerLevel level() {
		return this.level;
	}

	public Vec3 centre() {
		return this.centre;
	}

	public Epoque epoque() {
		return this.epoque;
	}

	public Etat etat() {
		return this.etat;
	}

	public boolean terminee() {
		return this.etat == Etat.TERMINEE;
	}

	// ------------------------------------------------------------------ ouverture

	public void ouvrir() {
		RandomSource random = this.level.getRandom();

		ItemStack sablier = Artefacts.creer(Artefact.SABLIER);
		this.coeur = Effets.objet(this.level, this.centre, sablier);
		this.coeur.setTransformation(new Transformation(null, null, new Vector3f(0.01F), null));
		this.coeur.setGlowingTag(true);
		this.coeur.setGlowColorOverride(this.epoque.rgb);
		this.coeur.setViewRange(4.0F);
		Ephemere.ajouter(this.level, this.coeur);

		for (int i = 0; i < ECLATS; i++) {
			BlockState etat = this.epoque.palette.get(i % this.epoque.palette.size()).defaultBlockState();
			Display.BlockDisplay eclat = Effets.bloc(this.level, this.centre, etat);
			eclat.setTransformation(transformationEclat(i, 0, 0.01F, random));
			eclat.setViewRange(4.0F);
			eclat.setBrightnessOverride(new net.minecraft.util.Brightness(15, 15));
			this.eclats.add(Ephemere.ajouter(this.level, eclat));
		}

		this.texte = EntityTypes.TEXT_DISPLAY.create(this.level, EntitySpawnReason.COMMAND);
		this.texte.setPos(this.centre.add(0, 2.1, 0));
		this.texte.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		this.texte.setBackgroundColor(0x60000000);
		this.texte.setViewRange(1.5F);
		Ephemere.ajouter(this.level, this.texte);
		mettreAJourTexte(Component.literal("Approche-toi pour la refermer").withStyle(ChatFormatting.GRAY));

		Effets.son(this.level, this.centre, SoundEvents.END_PORTAL_SPAWN, 2.0F, 1.6F);
		Effets.particulesLointaines(this.level, ParticleTypes.REVERSE_PORTAL, this.centre, 120, 0.3, 0.3, 0.3, 0.6);
	}

	/** Position d'un éclat sur son orbite inclinée. */
	private Transformation transformationEclat(final int i, final int tick, final float echelle, final RandomSource random) {
		double angle = Mth.TWO_PI * i / ECLATS + tick * 0.045;
		double inclinaison = (i % 2 == 0 ? 0.35 : -0.35);
		float x = (float) (Math.cos(angle) * RAYON_ORBITE);
		float z = (float) (Math.sin(angle) * RAYON_ORBITE);
		float y = (float) (Math.sin(angle * 2 + i) * 0.35 + x * inclinaison);
		Quaternionf rotation = new Quaternionf().rotateXYZ(tick * 0.08F + i, tick * 0.11F + i * 0.7F, tick * 0.05F);
		return Effets.transformationCentree(new Vector3f(x - 0.5F, y - 0.5F, z - 0.5F), rotation, echelle);
	}

	private void mettreAJourTexte(final Component statut) {
		this.texte.setText(Component.empty()
				.append(Component.literal("⌛ Faille temporelle ⌛").withStyle(this.epoque.couleur, ChatFormatting.BOLD))
				.append("\n")
				.append(Component.literal(this.epoque.nom).withStyle(this.epoque.couleur, ChatFormatting.ITALIC))
				.append("\n")
				.append(statut));
	}

	// ------------------------------------------------------------------ boucle

	public void tick() {
		this.age++;
		this.tickEtat++;
		if (!this.level.isLoaded(BlockPos.containing(this.centre))) {
			// Personne aux alentours : la faille s'éteint doucement sans recharger le terrain
			if (this.age > ChronoConfig.get().failles.dureeVie * 20) {
				fermer();
			}
			return;
		}
		animer();

		switch (this.etat) {
			case DORMANTE -> tickDormante();
			case EVEILLEE -> tickEveillee();
			case ENTRE_VAGUES -> tickEntreVagues();
			case EFFONDREMENT -> tickEffondrement();
			case TERMINEE -> {
			}
		}
	}

	private void animer() {
		RandomSource random = this.level.getRandom();
		float echelle = switch (this.etat) {
			case EFFONDREMENT -> 0.45F * (1.0F - Math.min(1.0F, this.tickEtat / (float) DUREE_EFFONDREMENT));
			case EVEILLEE, ENTRE_VAGUES -> 0.5F;
			default -> 0.4F;
		};
		if (this.age == 2 || this.age % PAS_ORBITE == 0) {
			int duree = this.age == 2 ? 30 : PAS_ORBITE;
			int cible = this.age == 2 ? 30 : this.age + PAS_ORBITE;
			for (int i = 0; i < this.eclats.size(); i++) {
				Effets.animer(this.eclats.get(i), transformationEclat(i, cible, echelle, random), duree);
			}
		}
		if (this.age == 2 || this.age % 10 == 0) {
			// Le sablier tourne sur lui-même en flottant
			float tours = (this.age + 10) * (this.etat == Etat.EFFONDREMENT ? 0.6F : 0.157F);
			float flottement = (float) Math.sin(this.age * 0.05) * 0.15F;
			float taille = this.etat == Etat.EFFONDREMENT ? Math.max(0.01F, 1.4F * echelle / 0.45F) : 1.4F;
			Effets.animer(this.coeur, new Transformation(new Vector3f(0, flottement, 0), new Quaternionf().rotateY(tours), new Vector3f(taille), null),
					this.age == 2 ? 30 : 10);
		}

		Effets.particules(this.level, ParticleTypes.PORTAL, this.centre, 4, 0.5, 0.6);
		if (this.age % 2 == 0) {
			Effets.particules(this.level, this.epoque.particule, this.centre, 2, 1.2, 0.02);
			Effets.particules(this.level, this.poussiere, this.centre, 2, 1.0, 0);
		}
		if (this.age % 10 == 0 && this.etat != Etat.EFFONDREMENT) {
			// Colonne visible de très loin : c'est elle qui guide les joueurs
			for (int h = 2; h < 40; h += 3) {
				Effets.particulesLointaines(this.level, this.poussiere, this.centre.add(0, h, 0), 1, 0.15, 0.4, 0.15, 0);
			}
			Effets.particulesLointaines(this.level, ParticleTypes.END_ROD, this.centre.add(0, 1.5, 0), 3, 0.1, 0.1, 0.1, 0.25);
		}
		if (this.age % 60 == 0) {
			Effets.son(this.level, this.centre, SoundEvents.PORTAL_AMBIENT, 0.5F, 1.4F);
			Effets.son(this.level, this.centre, SoundEvents.BEACON_AMBIENT, 1.0F, 0.6F);
		}
		accelererLeTemps(random);
	}

	/** Autour de la faille, le temps s'emballe : les plantes poussent à vue d'œil. */
	private void accelererLeTemps(final RandomSource random) {
		BlockPos base = BlockPos.containing(this.centre);
		for (int i = 0; i < 6; i++) {
			BlockPos pos = base.offset(random.nextInt(13) - 6, random.nextInt(7) - 4, random.nextInt(13) - 6);
			BlockState etat = this.level.getBlockState(pos);
			if (etat.isRandomlyTicking()) {
				etat.randomTick(this.level, pos, random);
			}
		}
	}

	private void tickDormante() {
		ChronoConfig.Failles config = ChronoConfig.get().failles;
		boolean eveil = false;
		for (ServerPlayer joueur : this.level.players()) {
			if (joueur.isSpectator()) {
				continue;
			}
			double d2 = joueur.position().distanceToSqr(this.centre);
			if (d2 < 24 * 24 && this.temoins.add(joueur.getUUID())) {
				Succes.FAILLE_VUE.accorder(joueur);
				joueur.sendSystemMessage(Component.literal("⌛ " + this.epoque.presage).withStyle(this.epoque.couleur, ChatFormatting.ITALIC));
			}
			if (d2 < DISTANCE_EVEIL * DISTANCE_EVEIL) {
				eveil = true;
			}
		}
		if (eveil) {
			eveiller();
		} else if (this.age > config.dureeVie * 20) {
			dissiper("La faille se referme d'elle-même, faute de témoins.");
		}
	}

	private void eveiller() {
		changerEtat(Etat.EVEILLEE);
		for (ServerPlayer joueur : joueursProches(32)) {
			joueur.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
			joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Faille temporelle").withStyle(this.epoque.couleur, ChatFormatting.BOLD)));
			joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(this.epoque.nom + " — défends cette époque !").withStyle(ChatFormatting.GRAY)));
		}
		Effets.son(this.level, this.centre, SoundEvents.TRIAL_SPAWNER_OMINOUS_ACTIVATE, 2.0F, 0.8F);
		Effets.son(this.level, this.centre, SoundEvents.BELL_RESONATE, 1.5F, 0.6F);
		Effets.particulesLointaines(this.level, ParticleTypes.SONIC_BOOM, this.centre, 1, 0, 0, 0, 0);
		lancerVague();
	}

	private void lancerVague() {
		this.vague++;
		RandomSource random = this.level.getRandom();
		int combattantsProches = Math.max(1, joueursProches(24).size());
		int nombre = Math.min(9, 1 + this.vague + combattantsProches);
		for (int i = 0; i < nombre; i++) {
			double angle = random.nextDouble() * Mth.TWO_PI;
			double distance = 3 + random.nextDouble() * 3;
			Vec3 pos = solAutour(this.centre.x + Math.cos(angle) * distance, this.centre.z + Math.sin(angle) * distance);
			Entity entity = this.epoque.vestiges.get(random.nextInt(this.epoque.vestiges.size())).create(this.level, EntitySpawnReason.EVENT);
			if (!(entity instanceof Mob vestige)) {
				continue;
			}
			vestige.snapTo(pos.x, pos.y, pos.z, random.nextFloat() * 360, 0);
			vestige.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(BlockPos.containing(pos)), EntitySpawnReason.EVENT, null);
			habiller(vestige);
			Ephemere.ajouter(this.level, vestige);
			this.vestiges.add(vestige);
			ServerPlayer cible = this.level.getNearestPlayer(vestige, 32) instanceof ServerPlayer p && !p.isCreative() ? p : null;
			if (cible != null) {
				vestige.setTarget(cible);
			}
			Effets.particules(this.level, ParticleTypes.REVERSE_PORTAL, pos.add(0, 1, 0), 40, 0.3, 0.15);
			Effets.ligne(this.level, this.poussiere, this.centre, pos.add(0, 1, 0), 0.4);
		}
		Effets.son(this.level, this.centre, SoundEvents.TRIAL_SPAWNER_SPAWN_MOB, 2.0F, 0.7F);
		Effets.son(this.level, this.centre, SoundEvents.EVOKER_CAST_SPELL, 1.5F, 0.6F);
		mettreAJourStatutCombat();
	}

	private void habiller(final Mob vestige) {
		vestige.setCustomName(Component.literal("Vestige · " + this.epoque.nom).withStyle(this.epoque.couleur));
		vestige.setPersistenceRequired();
		vestige.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
		// Un casque teinté aux couleurs de l'époque : il les protège aussi du soleil
		ItemStack casque = new ItemStack(Items.LEATHER_HELMET);
		casque.set(DataComponents.DYED_COLOR, new DyedItemColor(this.epoque.rgb));
		vestige.setItemSlot(EquipmentSlot.HEAD, casque);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			vestige.setDropChance(slot, 0.0F);
		}
	}

	/** Un point au sol près de (x, z), à une hauteur raisonnable par rapport à la faille. */
	private Vec3 solAutour(final double x, final double z) {
		int y = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
		if (Math.abs(y - this.centre.y) > 6) {
			return new Vec3(this.centre.x, this.centre.y - 1.5, this.centre.z);
		}
		return new Vec3(x, y, z);
	}

	private void tickEveillee() {
		this.vestiges.removeIf(v -> !v.isAlive() || v.isRemoved());
		for (ServerPlayer joueur : joueursProches(24)) {
			this.combattants.add(joueur.getUUID());
		}
		if (this.tickEtat % 10 == 0) {
			mettreAJourStatutCombat();
		}
		if (this.vestiges.isEmpty()) {
			if (this.vague >= Math.max(1, ChronoConfig.get().failles.vagues)) {
				changerEtat(Etat.EFFONDREMENT);
				mettreAJourTexte(Component.literal("La faille s'effondre !").withStyle(ChatFormatting.YELLOW));
				Effets.son(this.level, this.centre, SoundEvents.BEACON_DEACTIVATE, 2.0F, 0.5F);
			} else {
				changerEtat(Etat.ENTRE_VAGUES);
				Effets.son(this.level, this.centre, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5F, 0.6F);
			}
			return;
		}
		verifierAbandon();
	}

	private void tickEntreVagues() {
		int restant = (PAUSE_ENTRE_VAGUES - this.tickEtat + 19) / 20;
		if (this.tickEtat % 20 == 1) {
			mettreAJourTexte(Component.literal("Vague " + (this.vague + 1) + " dans " + restant + "…").withStyle(ChatFormatting.YELLOW));
			Effets.son(this.level, this.centre, SoundEvents.NOTE_BLOCK_HAT, 1.0F, 0.8F);
		}
		if (this.tickEtat >= PAUSE_ENTRE_VAGUES) {
			changerEtat(Etat.EVEILLEE);
			lancerVague();
		}
	}

	private void mettreAJourStatutCombat() {
		int total = Math.max(1, ChronoConfig.get().failles.vagues);
		mettreAJourTexte(Component.literal("Vague " + this.vague + "/" + total + " — " + this.vestiges.size() + " vestige" + (this.vestiges.size() > 1 ? "s" : ""))
				.withStyle(ChatFormatting.RED));
	}

	private void verifierAbandon() {
		if (joueursProches(DISTANCE_ABANDON).isEmpty()) {
			this.ticksSansPersonne++;
			if (this.ticksSansPersonne > 30 * 20) {
				dissiper("Abandonnée, la faille se referme et emporte ses vestiges.");
			}
		} else {
			this.ticksSansPersonne = 0;
		}
	}

	private void tickEffondrement() {
		if (this.tickEtat % 5 == 0) {
			Effets.son(this.level, this.centre, SoundEvents.NOTE_BLOCK_HAT, 1.0F, 0.5F + this.tickEtat / (float) DUREE_EFFONDREMENT * 1.5F);
		}
		Effets.particules(this.level, ParticleTypes.REVERSE_PORTAL, this.centre, 10, 1.5, 0.3);
		if (this.tickEtat >= DUREE_EFFONDREMENT) {
			recompenser();
			fermer();
		}
	}

	// ------------------------------------------------------------------ fin

	private void recompenser() {
		RandomSource random = this.level.getRandom();
		Effets.particulesLointaines(this.level, ParticleTypes.EXPLOSION_EMITTER, this.centre, 1, 0, 0, 0, 0);
		Effets.particulesLointaines(this.level, ParticleTypes.END_ROD, this.centre, 80, 0.2, 0.2, 0.2, 0.4);
		Effets.sphere(this.level, this.poussiere, this.centre, 3.0, 80, 0);
		Effets.son(this.level, this.centre, SoundEvents.TOTEM_USE, 1.2F, 1.2F);
		Effets.son(this.level, this.centre, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.0F, 0.5F);

		int vagues = Math.max(1, ChronoConfig.get().failles.vagues);
		int fragments = 1 + vagues + random.nextInt(2) + Math.max(0, this.combattants.size() - 1);
		jaillir(Artefacts.creer(Artefact.FRAGMENT, fragments));
		for (int i = 0; i < 2 + random.nextInt(3); i++) {
			jaillir(new ItemStack(this.epoque.tresors.get(random.nextInt(this.epoque.tresors.size())), 1 + random.nextInt(4)));
		}
		ExperienceOrb.award(this.level, this.centre, 20 + 10 * vagues);

		Component annonce = Component.literal("⌛ Une faille ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(this.epoque.complement).withStyle(this.epoque.couleur))
				.append(Component.literal(" a été refermée !").withStyle(ChatFormatting.GRAY));
		for (UUID id : this.combattants) {
			if (this.level.getServer().getPlayerList().getPlayer(id) instanceof ServerPlayer joueur) {
				joueur.sendSystemMessage(annonce);
				Succes.FAILLE_REFERMEE.accorder(joueur);
				joueur.addTag("chronomancie.ere." + this.epoque.id);
				boolean toutes = true;
				for (Epoque e : Epoque.values()) {
					toutes &= joueur.entityTags().contains("chronomancie.ere." + e.id);
				}
				if (toutes) {
					Succes.MAITRE.accorder(joueur);
				}
			}
		}
	}

	private void jaillir(final ItemStack stack) {
		RandomSource random = this.level.getRandom();
		ItemEntity item = new ItemEntity(this.level, this.centre.x, this.centre.y, this.centre.z, stack,
				(random.nextDouble() - 0.5) * 0.3, 0.35 + random.nextDouble() * 0.2, (random.nextDouble() - 0.5) * 0.3);
		item.setDefaultPickUpDelay();
		this.level.addFreshEntity(item);
	}

	private void dissiper(final String raison) {
		for (ServerPlayer joueur : joueursProches(64)) {
			joueur.sendSystemMessage(Component.literal("⌛ " + raison).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		}
		Effets.son(this.level, this.centre, SoundEvents.BEACON_DEACTIVATE, 1.5F, 1.4F);
		Effets.particules(this.level, ParticleTypes.REVERSE_PORTAL, this.centre, 80, 0.5, 0.4);
		fermer();
	}

	/** Retire tout ce que la faille a fait apparaître. */
	public void fermer() {
		for (Mob vestige : this.vestiges) {
			if (vestige.isAlive()) {
				Effets.particules(this.level, ParticleTypes.REVERSE_PORTAL, vestige.position().add(0, 1, 0), 20, 0.3, 0.1);
			}
			Ephemere.retirer(vestige);
		}
		this.vestiges.clear();
		this.eclats.forEach(Ephemere::retirer);
		this.eclats.clear();
		Ephemere.retirer(this.coeur);
		Ephemere.retirer(this.texte);
		this.etat = Etat.TERMINEE;
	}

	private void changerEtat(final Etat nouvel) {
		this.etat = nouvel;
		this.tickEtat = 0;
	}

	private List<ServerPlayer> joueursProches(final double distance) {
		List<ServerPlayer> proches = new ArrayList<>();
		for (ServerPlayer joueur : this.level.players()) {
			if (!joueur.isSpectator() && joueur.position().distanceToSqr(this.centre) < distance * distance) {
				proches.add(joueur);
			}
		}
		return proches;
	}
}
