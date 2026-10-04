package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Les constructions : un plan imaginé par Claude, puis posé bloc par bloc par le villageois et ceux qui l'aident. */
public final class Chantiers {
	private static final String[] INTERDITS = {"tnt", "lava", "fire", "bedrock", "barrier", "command", "structure", "portal", "spawner", "jigsaw", "end_", "bed", "chest", "shulker", "piston", "dispenser", "dropper", "hopper", "observer"};
	/** Par ouvrier : tick de la prochaine pose, et tick du dernier bloc réellement posé. */
	private static final Map<UUID, long[]> RYTHME = new HashMap<>();

	private Chantiers() {
	}

	/** Le villageois imagine son bâtiment : un appel au modèle des plans, puis le chantier commence. */
	static void commander(final Villager villageois, final Ame ame, final BlockPos centre, final String description, final long maintenant) {
		MinecraftServer server = villageois.level().getServer();
		UUID uuid = villageois.getUUID();
		ame.planEnCours = true;
		Bulles.montrer(villageois, "* trace un plan dans la poussière *", true, maintenant);
		Corps.accroupir(villageois, 60);
		List<String> poches = new ArrayList<>();
		for (ItemStack pile : villageois.getInventory().getItems()) {
			if (!pile.isEmpty()) {
				poches.add(Perception.objet(pile));
			}
		}
		String demande = "Construction demandée : " + description + "\nBâtisseur : " + ame.nom + ", " + ame.metier + ". Caractère : " + String.join(", ", ame.traits)
				+ ". Manie : " + ame.manie + ". Il désire : " + ame.desir + ".\nCe qu'il a en poche : " + (poches.isEmpty() ? "rien" : String.join(", ", poches))
				+ (HameauConfig.get().batir.materiauxGratuits ? "" : "\nIl devra réunir lui-même chaque bloc : privilégie le bois et les matériaux faciles à trouver.");
		Cerveau.dessiner(demande).whenComplete((plan, erreur) -> server.execute(() -> {
			ame.planEnCours = false;
			Villager present = Vie.trouver(server, uuid);
			if (erreur != null || plan == null || present == null) {
				Hameau.LOGGER.warn("Hameau : pas de plan pour {} : {}", ame.nom, erreur);
				ame.noter("Tu n'as pas réussi à imaginer le plan de ta construction. Il faudra y repenser.");
				return;
			}
			HameauConfig config = HameauConfig.get();
			if (plan.secours()) {
				Ames.budget.enregistrer(plan.perdusEntree(), plan.perdusSortie(), config.batir.prixEntreeParMillion, config.batir.prixSortieParMillion);
				Ames.budget.enregistrer(plan.tokensEntree(), plan.tokensSortie());
			} else {
				Ames.budget.enregistrer(plan.tokensEntree(), plan.tokensSortie(), config.batir.prixEntreeParMillion, config.batir.prixSortieParMillion);
			}
			ServerLevel level = (ServerLevel) present.level();
			for (int c = 0; c < plan.couches().size(); c++) {
				Hameau.LOGGER.info("[{}] plan « {} » couche {} : {}", ame.nom, plan.nom(), c, String.join(" | ", plan.couches().get(c)));
			}
			Hameau.LOGGER.info("[{}] palette : {}", ame.nom, plan.palette());
			// Un pont, un quai, un ajout à une bâtisse existante se font là où on l'a dit ; tout le reste cherche un terrain plat et libre.
			boolean surPlace = Ames.simplifier(description).matches(".*\\b(pont|ponton|passerelle|quai|jetee|port|barrage|digue|agrandi\\w*|rallonge|annexe|toit|etage|reparer|repare)\\b.*");
			Ame.Chantier chantier = tracer(plan, centre, level, !surPlace);
			if (chantier == null) {
				ame.noter("Tu n'as trouvé aucun terrain plat et dégagé à moins de " + RAYON_TERRAIN + " blocs de " + Perception.coord(centre)
						+ " pour bâtir « " + plan.nom() + " » : trop de relief, d'eau ou de constructions. Il faudra choisir un autre endroit, à l'écart.");
				return;
			}
			if (chantier.restants.isEmpty()) {
				ame.noter("Ton plan ne contenait rien de constructible. Il faudra y repenser.");
				return;
			}
			ame.chantier = chantier;
			String ou = chantier.x + " " + chantier.y + " " + chantier.z;
			ame.noter("Tu as dessiné le plan de « " + chantier.nom + " » (" + chantier.total + " blocs) et tu commences à bâtir en " + ou + ".");
			Vie.temoins(present, null, ame.nom + " commence à bâtir « " + chantier.nom + " » en " + ou + ".", false);
			Bulles.montrer(present, "* se met à bâtir : " + chantier.nom + " *", true, server.getTickCount());
			Hameau.LOGGER.info("[{}] chantier « {} » : {} blocs en {} ({} tokens en sortie{})", ame.nom, chantier.nom, chantier.total, ou, plan.tokensSortie(), plan.secours() ? ", modèle de secours" : "");
		}));
	}

	private static Block bloc(final String identifiant) {
		if (identifiant == null) {
			return null;
		}
		String id = identifiant.trim().toLowerCase();
		for (String interdit : INTERDITS) {
			if (id.contains(interdit)) {
				return null;
			}
		}
		Identifier cle = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		return cle == null ? null : BuiltInRegistries.BLOCK.getOptional(cle).orElse(null);
	}

	/** Transforme le plan en liste de blocs à poser : du bas vers le haut, les éléments fragiles (portes, torches) en dernier. */
	static final int RAYON_TERRAIN = 24;

	/** Le niveau du sol dans cette colonne, sous les arbres et les herbes. */
	private static int sol(final ServerLevel level, final int x, final int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY() - 1, z);
		for (int descente = 0; descente < 16; descente++) {
			BlockState etat = level.getBlockState(pos);
			if (!etat.is(BlockTags.LOGS) && !etat.is(BlockTags.LEAVES) && !(etat.canBeReplaced() && etat.getFluidState().isEmpty())) {
				break;
			}
			pos.move(Direction.DOWN);
		}
		return pos.getY();
	}

	/** Un sol sur lequel on peut fonder : terre, sable, roche — ni eau, ni chemin, ni champ, ni ouvrage de quelqu'un. */
	private static boolean constructible(final BlockState etat) {
		return etat.getFluidState().isEmpty() && (etat.is(BlockTags.DIRT) || etat.is(BlockTags.BASE_STONE_OVERWORLD) || etat.is(BlockTags.SAND) || etat.is(BlockTags.TERRACOTTA)
				|| etat.is(BlockTags.SNOW) || etat.is(Blocks.GRAVEL) || etat.is(Blocks.CLAY) || etat.is(Blocks.SANDSTONE) || etat.is(Blocks.RED_SANDSTONE) || etat.is(Blocks.SNOW_BLOCK));
	}

	/**
	 * Cherche autour de l'endroit voulu le terrain le plus proche qui convienne à l'emprise : à peu près plat, sans eau, et où rien n'a été bâti.
	 * @return le coin nord-ouest de l'emprise, ou null s'il n'y a rien de convenable à portée
	 */
	private static BlockPos terrain(final ServerLevel level, final BlockPos centre, final int largeur, final int profondeur, final int hauteur) {
		List<int[]> decalages = new ArrayList<>();
		for (int dx = -RAYON_TERRAIN; dx <= RAYON_TERRAIN; dx += 3) {
			for (int dz = -RAYON_TERRAIN; dz <= RAYON_TERRAIN; dz += 3) {
				decalages.add(new int[] {dx, dz});
			}
		}
		decalages.sort(java.util.Comparator.comparingInt(d -> d[0] * d[0] + d[1] * d[1]));
		BlockPos passable = null;
		for (int[] decalage : decalages) {
			int ox = centre.getX() + decalage[0] - largeur / 2;
			int oz = centre.getZ() + decalage[1] - profondeur / 2;
			if (!level.isLoaded(new BlockPos(ox, centre.getY(), oz)) || !level.isLoaded(new BlockPos(ox + largeur, centre.getY(), oz + profondeur))) {
				continue;
			}
			int bas = Integer.MAX_VALUE;
			int haut = Integer.MIN_VALUE;
			boolean libre = true;
			// Une marge d'un bloc autour de l'emprise : on ne colle pas ses murs à ceux du voisin.
			for (int i = -1; i <= largeur && libre; i++) {
				for (int l = -1; l <= profondeur && libre; l++) {
					int y = sol(level, ox + i, oz + l);
					bas = Math.min(bas, y);
					haut = Math.max(haut, y);
					libre = haut - bas <= 4 && constructible(level.getBlockState(new BlockPos(ox + i, y, oz + l)));
				}
			}
			if (!libre) {
				continue;
			}
			// Rien de bâti non plus dans le volume : un auvent, une clôture, une lanterne trahissent un lieu déjà occupé.
			for (int i = 0; i < largeur && libre; i++) {
				for (int l = 0; l < profondeur && libre; l++) {
					for (int y = bas + 1; y <= haut + hauteur && libre; y++) {
						BlockState etat = level.getBlockState(new BlockPos(ox + i, y, oz + l));
						libre = etat.isAir() || terrain(etat);
					}
				}
			}
			if (!libre) {
				continue;
			}
			if (haut - bas <= 2) {
				return new BlockPos(ox, 0, oz);
			}
			if (passable == null) {
				passable = new BlockPos(ox, 0, oz);
			}
		}
		return passable;
	}

	private static Ame.Chantier tracer(final Cerveau.Plan plan, final BlockPos centre, final ServerLevel level, final boolean chercherTerrain) {
		HameauConfig.Batir reglages = HameauConfig.get().batir;
		int hauteur = Math.min(plan.couches().size(), reglages.hauteurMax);
		int profondeur = 0;
		int largeur = 0;
		for (int c = 0; c < hauteur; c++) {
			profondeur = Math.max(profondeur, Math.min(plan.couches().get(c).size(), reglages.largeurMax));
			for (String ligne : plan.couches().get(c)) {
				largeur = Math.max(largeur, Math.min(ligne.length(), reglages.largeurMax));
			}
		}
		Ame.Chantier chantier = new Ame.Chantier();
		chantier.nom = plan.nom();
		int ox = centre.getX() - largeur / 2;
		int oz = centre.getZ() - profondeur / 2;
		if (chercherTerrain) {
			BlockPos coin = terrain(level, centre, largeur, profondeur, hauteur);
			if (coin == null) {
				return null;
			}
			ox = coin.getX();
			oz = coin.getZ();
		}
		chantier.x = ox + largeur / 2;
		chantier.z = oz + profondeur / 2;
		// Le sol du bâtiment remplace la couche supérieure du terrain, au niveau médian de l'emprise :
		// une butte sera entaillée, un creux comblé par les fondations.
		List<Integer> niveaux = new ArrayList<>();
		for (int i = 0; i < largeur; i += 2) {
			for (int l = 0; l < profondeur; l += 2) {
				niveaux.add(sol(level, ox + i, oz + l));
			}
		}
		niveaux.sort(null);
		chantier.y = niveaux.isEmpty() ? centre.getY() - 1 : niveaux.get(niveaux.size() / 2);
		List<String> fragiles = new ArrayList<>();
		// Terrassement : on dégage le terrain qui empiète sur le volume du bâtiment (butte, arbre, neige), du haut vers le bas…
		for (int c = hauteur; c >= 1; c--) {
			List<String> lignes = c < hauteur ? plan.couches().get(c) : List.of();
			for (int l = 0; l < profondeur; l++) {
				for (int i = 0; i < largeur; i++) {
					boolean vide = l >= lignes.size() || i >= lignes.get(l).length() || ".~ ".indexOf(lignes.get(l).charAt(i)) >= 0;
					BlockPos pos = new BlockPos(ox + i, chantier.y + c, oz + l);
					if (vide && terrain(level.getBlockState(pos))) {
						chantier.restants.add(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:air");
					}
				}
			}
		}
		// … et on fonde le pourtour là où le sol se dérobe, pour que rien ne flotte.
		if (!plan.couches().isEmpty()) {
			List<String> sol = plan.couches().get(0);
			for (int l = 0; l < Math.min(sol.size(), profondeur); l++) {
				for (int i = 0; i < Math.min(sol.get(l).length(), largeur); i++) {
					boolean bord = l == 0 || i == 0 || l == Math.min(sol.size(), profondeur) - 1 || i == Math.min(sol.get(l).length(), largeur) - 1;
					if (!bord || ".~ ".indexOf(sol.get(l).charAt(i)) >= 0) {
						continue;
					}
					for (int d = 1; d <= 5; d++) {
						BlockPos dessous = new BlockPos(ox + i, chantier.y - d, oz + l);
						BlockState etat = level.getBlockState(dessous);
						if (!etat.canBeReplaced() && etat.getFluidState().isEmpty()) {
							break;
						}
						chantier.restants.add(dessous.getX() + " " + dessous.getY() + " " + dessous.getZ() + " minecraft:cobblestone");
					}
				}
			}
		}
		for (int c = 0; c < hauteur; c++) {
			List<String> lignes = plan.couches().get(c);
			for (int l = 0; l < Math.min(lignes.size(), profondeur); l++) {
				String ligne = lignes.get(l);
				for (int i = 0; i < Math.min(ligne.length(), largeur); i++) {
					String caractere = String.valueOf(ligne.charAt(i));
					// « ~ » : creuser, vider cet emplacement.
					Block bloc = caractere.equals(".") || caractere.equals(" ") ? null : caractere.equals("~") ? Blocks.AIR : bloc(plan.palette().get(caractere));
					if (bloc == null || (bloc == Blocks.AIR && !caractere.equals("~"))) {
						continue;
					}
					String entree = (ox + i) + " " + (chantier.y + c) + " " + (oz + l) + " " + BuiltInRegistries.BLOCK.getKey(bloc);
					if (bloc instanceof DoorBlock) {
						// La porte se couche dans le plan du mur qui la porte.
						fragiles.add(entree + " " + (i == 0 || i == largeur - 1 ? "east" : "north"));
					} else if (bloc.defaultBlockState().getCollisionShape(level, BlockPos.ZERO).isEmpty()) {
						fragiles.add(entree);
					} else {
						chantier.restants.add(entree);
					}
				}
			}
		}
		chantier.restants.addAll(fragiles);
		chantier.total = chantier.restants.size();
		return chantier;
	}

	private static boolean terrain(final BlockState etat) {
		return etat.is(BlockTags.DIRT) || etat.is(BlockTags.BASE_STONE_OVERWORLD) || etat.is(BlockTags.SAND) || etat.is(BlockTags.LEAVES) || etat.is(BlockTags.LOGS)
				|| etat.is(BlockTags.SNOW) || etat.is(Blocks.GRAVEL) || etat.is(Blocks.CLAY) || (!etat.isAir() && etat.canBeReplaced() && etat.getFluidState().isEmpty());
	}

	static void oublier(final UUID ouvrier) {
		RYTHME.remove(ouvrier);
	}

	private static void marcher(final Villager villageois, final Vec3 vers) {
		villageois.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(vers, 0.6F, 2));
	}

	/**
	 * Un pas de chantier pour cet ouvrier (le propriétaire ou quelqu'un qui l'aide).
	 * @return vrai quand il n'a plus rien à y faire (terminé, abandonné, ou matériaux manquants).
	 */
	static boolean avancer(final Villager villageois, final Ame ouvrier, final Ame proprietaire, final ServerLevel level, final long maintenant) {
		Ame.Chantier chantier = proprietaire.chantier;
		if (chantier == null) {
			if (proprietaire.planEnCours) {
				villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
				return false;
			}
			return true;
		}
		HameauConfig.Batir reglages = HameauConfig.get().batir;
		// [prochaine pose, dernière pose, 1 si la dernière pose s'est faite de loin faute de pouvoir approcher]
		long[] rythme = RYTHME.computeIfAbsent(villageois.getUUID(), u -> new long[] {0, maintenant, 0});
		if (maintenant < rythme[0]) {
			return false;
		}
		// On cherche, dans les premiers blocs à poser, un emplacement libre de toute créature.
		for (int rang = 0; rang < Math.min(chantier.restants.size(), 12); rang++) {
			String[] parties = chantier.restants.get(rang).split(" ");
			BlockPos pos = new BlockPos(Integer.parseInt(parties[0]), Integer.parseInt(parties[1]), Integer.parseInt(parties[2]));
			Block bloc = bloc(parties[3]);
			if (bloc == null) {
				chantier.restants.remove(rang);
				return false;
			}
			BlockState voulu = bloc.defaultBlockState();
			BlockState actuel = level.getBlockState(pos);
				boolean creuse = bloc == Blocks.AIR;
			if (actuel.is(bloc) || actuel.hasBlockEntity() || actuel.getDestroySpeed(level, pos) < 0) {
				// Déjà en place, ou quelque chose d'intouchable occupe l'endroit : on passe.
				chantier.restants.remove(rang);
				return false;
			}
			boolean encombrant = !voulu.getCollisionShape(level, pos).isEmpty();
			List<LivingEntity> genants = encombrant ? level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> e.isAlive() && !(e instanceof Mannequin)) : List.of();
			if (!genants.isEmpty()) {
				// Un villageois planté là où doit monter un mur s'écarte ; un joueur, on attend qu'il bouge.
				for (LivingEntity genant : genants) {
					if (genant instanceof Villager autre) {
						Vec3 fuite = autre.position().subtract(chantier.x + 0.5, autre.getY(), chantier.z + 0.5);
						Vec3 direction = fuite.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : fuite.normalize();
						marcher(autre, autre.position().add(direction.scale(4)));
					}
				}
				continue;
			}
			double dx = villageois.getX() - (pos.getX() + 0.5);
			double dz = villageois.getZ() - (pos.getZ() + 0.5);
			boolean aPortee = dx * dx + dz * dz < 30 && Math.abs(villageois.getY() - pos.getY()) < 10;
			if (!aPortee) {
				long attente = maintenant - rythme[1];
				if (attente < (rythme[2] == 1 ? 40 : 100)) {
					marcher(villageois, new Vec3(pos.getX() + 0.5, chantier.y + 1, pos.getZ() + 0.5));
					return false;
				}
				if (rythme[2] == 0) {
					// Coincé (tombé dans un trou, enfermé, relief) : on le ramène au bord de l'ouvrage.
					int ex = pos.getX() + 2 * Integer.signum(pos.getX() - chantier.x == 0 ? 1 : pos.getX() - chantier.x);
					int ez = pos.getZ() + 2 * Integer.signum(pos.getZ() - chantier.z);
					BlockPos bord = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(ex, 0, ez));
					villageois.teleportTo(bord.getX() + 0.5, bord.getY(), bord.getZ() + 0.5);
					villageois.getNavigation().stop();
					rythme[1] = maintenant;
					rythme[2] = 1;
					return false;
				}
				// Toujours hors de portée après cela : il pose ce bloc de loin.
			}
			Item objet = creuse ? net.minecraft.world.item.Items.IRON_SHOVEL : bloc == Blocks.WATER ? net.minecraft.world.item.Items.WATER_BUCKET : bloc.asItem();
			if (!creuse && bloc != Blocks.WATER && !reglages.materiauxGratuits && !fournir(villageois.getInventory(), objet)) {
				String manque = bloc.getName().getString();
				ouvrier.noter("Chantier « " + chantier.nom + " » interrompu : il te manque « " + manque + " » (encore " + chantier.restants.size() + " blocs à poser). Trouve-en, puis reprends avec \"batir\".");
				RYTHME.remove(villageois.getUUID());
				return true;
			}
			villageois.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
			villageois.getLookControl().setLookAt(Vec3.atCenterOf(pos));
			Corps.tenir(villageois, new ItemStack(objet));
			Corps.balancer(villageois);
			if (!actuel.isAir()) {
				level.destroyBlock(pos, false, villageois, 512);
			}
			if (creuse) {
				chantier.restants.remove(rang);
				rythme[0] = maintenant + reglages.ticksParBloc;
				rythme[1] = maintenant;
				break;
			}
			if (bloc instanceof DoorBlock) {
				Direction face = parties.length > 4 && parties[4].equals("east") ? Direction.EAST : Direction.NORTH;
				voulu = voulu.setValue(BlockStateProperties.HORIZONTAL_FACING, face);
				level.setBlockAndUpdate(pos, voulu.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
				level.setBlockAndUpdate(pos.above(), voulu.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
			} else {
				level.setBlockAndUpdate(pos, Block.updateFromNeighbourShapes(voulu, level, pos));
			}
			level.playSound(null, pos, voulu.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1F, 0.9F + level.getRandom().nextFloat() * 0.2F);
			chantier.restants.remove(rang);
			rythme[0] = maintenant + reglages.ticksParBloc;
			rythme[1] = maintenant;
			rythme[2] = 0;
			break;
		}
		if (!chantier.restants.isEmpty() && maintenant - rythme[1] > 600) {
			// Plus rien de posable depuis trente secondes (quelqu'un campe sur les derniers emplacements) : on en reste là.
			chantier.restants.clear();
		}
		if (chantier.restants.isEmpty()) {
			String ou = chantier.x + " " + chantier.y + " " + chantier.z;
			proprietaire.chantier = null;
			RYTHME.remove(villageois.getUUID());
			proprietaire.noter("Ta construction « " + chantier.nom + " » est achevée, en " + ou + ".");
			proprietaire.retenir("(" + Perception.moment(level) + ") Tu as bâti « " + chantier.nom + " » en " + ou + ".");
			if (ouvrier != proprietaire) {
				ouvrier.noter("Tu as aidé " + proprietaire.nom + " à achever « " + chantier.nom + " ».");
				proprietaire.ajusterRelation(ouvrier.nom, 12);
			}
			Vie.temoins(villageois, null, proprietaire.nom + " a achevé de bâtir « " + chantier.nom + " » en " + ou + ".", false);
			Bulles.emouvoir(villageois, ouvrier, "joie", false);
			Hameau.LOGGER.info("[{}] chantier « {} » achevé", proprietaire.nom, chantier.nom);
			return true;
		}
		return false;
	}

	/** Sort le bloc des poches ; à défaut de planches, débite une bûche en quatre. */
	private static boolean fournir(final SimpleContainer poches, final Item objet) {
		for (int i = 0; i < poches.getContainerSize(); i++) {
			if (poches.getItem(i).is(objet)) {
				poches.removeItem(i, 1);
				return true;
			}
		}
		if (new ItemStack(objet).is(ItemTags.PLANKS)) {
			for (int i = 0; i < poches.getContainerSize(); i++) {
				if (poches.getItem(i).is(ItemTags.LOGS)) {
					poches.removeItem(i, 1);
					poches.addItem(new ItemStack(objet, 3));
					return true;
				}
			}
		}
		return false;
	}
}
