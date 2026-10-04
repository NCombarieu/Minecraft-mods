package com.ncombarieu.hameau;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Scénarios joués dans un vrai serveur. Aucun modèle n'est appelé : sans clé d'API, les villageois ne réfléchissent pas,
 * et les tests leur donnent directement les décisions qu'un modèle aurait prises.
 */
public class HameauTests {
	/** Des chantiers rapides et sans attente d'avis : les tests n'ont pas de joueur pour cliquer. */
	private static void reglagesDeTest() {
		HameauConfig.Batir batir = HameauConfig.get().batir;
		batir.ticksParBloc = 1;
		batir.apercuSecondes = 0;
		batir.materiauxGratuits = true;
		HameauConfig.get().corps = false;
	}

	private static void sol(final GameTestHelper helper, final int cote) {
		reglagesDeTest();
		for (int x = 0; x < cote; x++) {
			for (int z = 0; z < cote; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	private static Villager villageois(final GameTestHelper helper, final int x, final int z) {
		Villager villageois = helper.spawn(EntityTypes.VILLAGER, new BlockPos(x, 1, z));
		villageois.setNoAi(false);
		return villageois;
	}

	/** Une décision toute faite, comme celle que rend /hameau faire. */
	private static Cerveau.Decision decision(final String action, final String cible, final String objet) {
		return new Cerveau.Decision(null, null, null, null, false, action, cible, objet, new ArrayList<>(), null, null, null, Map.of(), null, null, null, null, 0, 0);
	}

	private static long maintenant(final GameTestHelper helper) {
		return helper.getLevel().getServer().getTickCount();
	}

	private static String coord(final BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	@GameTest
	public void uneAmeNaitAvecLeVillageois(final GameTestHelper helper) {
		sol(helper, 4);
		Villager villageois = villageois(helper, 1, 1);
		Ame ame = Ames.de(villageois);
		helper.assertTrue(ame.nom != null && !ame.nom.isBlank(), "Le villageois doit recevoir un prénom");
		helper.assertTrue(ame.ebauche, "Sa personnalité reste à inventer par le modèle");
		helper.assertTrue(!ame.traits.isEmpty() && ame.manie != null && ame.parler != null, "Le brouillon tiré au sort doit être complet");
		helper.assertTrue(Ames.de(villageois) == ame, "La même âme est rendue à chaque fois");
		helper.assertTrue(Ames.parNom(Ames.simplifier(ame.nom).toUpperCase()) == ame, "On le retrouve par son prénom, sans souci de casse ni d'accents");
		helper.assertTrue(!ame.etranger, "Un villageois né normalement n'est pas un étranger");
		helper.succeed();
	}

	@GameTest
	public void unVillageoisInvoqueEstUnEtranger(final GameTestHelper helper) {
		sol(helper, 4);
		Vec3 ou = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
		// Même chemin que /summon : la raison de l'apparition est connue du villageois dès sa création.
		Villager invoque = EntityTypes.VILLAGER.spawn(helper.getLevel(), BlockPos.containing(ou), EntitySpawnReason.COMMAND);
		helper.assertTrue(invoque != null && invoque.entityTags().contains(Ames.TAG_ETRANGER), "Un villageois invoqué doit porter la marque de l'étranger");
		Ame ame = Ames.de(invoque);
		helper.assertTrue(ame.etranger, "Son âme doit le savoir étranger");
		helper.assertTrue(ame.village == null, "Il n'appartient à aucun village");
		Ames.Village village = Ames.rattacher(invoque, ame);
		helper.assertTrue(Ames.village(ame) == village, "Une fois présenté, il est rattaché au village");
		helper.succeed();
	}

	@GameTest(maxTicks = 400)
	public void unPlanEstBatiAvecSesBlocsOrientes(final GameTestHelper helper) {
		sol(helper, 8);
		Villager villageois = villageois(helper, 6, 6);
		Ame ame = Ames.de(villageois);
		Cerveau.Plan plan = new Cerveau.Plan("Abri d'essai",
				Map.of("P", "minecraft:oak_planks", "E", "minecraft:oak_stairs[facing=south,half=bottom]", "L", "minecraft:oak_log[axis=x]",
						"D", "minecraft:oak_door[facing=east]", "X", "minecraft:tnt", "T", "minecraft:torch"),
				List.of(List.of("PPP", "PPP", "PPP"), List.of("LDL", "T.X", "PPP"), List.of("EEE", "...", "PPP")), 0, 0, false, 0, 0);
		BlockPos centre = helper.absolutePos(new BlockPos(2, 1, 2));
		Ame.Chantier chantier = Chantiers.tracer(plan, centre, helper.getLevel(), false);
		helper.assertTrue(chantier != null && chantier.total > 0, "Le plan doit donner des blocs à poser");
		helper.assertTrue(chantier.restants.stream().noneMatch(bloc -> bloc.contains("tnt")), "Un bloc interdit ne doit pas entrer dans le chantier");
		helper.assertTrue(chantier.restants.getLast().contains("torch") || chantier.restants.getLast().contains("door"), "Torches et portes se posent en dernier");
		ame.chantier = chantier;
		Actions.lancer(villageois, ame, decision("batir", null, null), maintenant(helper));
		BlockPos coin = new BlockPos(chantier.x - chantier.largeur / 2, chantier.y, chantier.z - chantier.profondeur / 2);
		ServerLevel level = helper.getLevel();
		helper.succeedWhen(() -> {
			helper.assertTrue(ame.chantier == null, "Le chantier doit s'achever");
			BlockState marche = level.getBlockState(coin.offset(0, 2, 0));
			helper.assertTrue(marche.is(Blocks.OAK_STAIRS) && marche.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.SOUTH, "L'escalier doit garder l'orientation du plan");
			BlockState buche = level.getBlockState(coin.offset(0, 1, 0));
			helper.assertTrue(buche.is(Blocks.OAK_LOG) && buche.getValue(BlockStateProperties.AXIS) == Direction.Axis.X, "La bûche doit être couchée comme le plan le dit");
			BlockState porte = level.getBlockState(coin.offset(1, 1, 0));
			helper.assertTrue(porte.getBlock() instanceof DoorBlock && porte.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "La porte doit regarder vers l'est");
			helper.assertTrue(level.getBlockState(coin.offset(1, 2, 0)).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER, "La moitié haute de la porte se pose toute seule");
			helper.assertTrue(level.getBlockState(coin.offset(2, 1, 1)).isAir(), "Le bloc interdit n'a pas été posé");
		});
	}

	@GameTest
	public void leTerrainChoisiEviteCeQuiEstDejaBati(final GameTestHelper helper) {
		reglagesDeTest();
		ServerLevel level = helper.getLevel();
		// Un sol de terre assez large pour offrir un autre emplacement, et une bâtisse au milieu de l'endroit demandé.
		for (int x = -12; x <= 12; x++) {
			for (int z = -12; z <= 12; z++) {
				level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.DIRT.defaultBlockState());
			}
		}
		BlockPos centre = helper.absolutePos(new BlockPos(0, 1, 0));
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				level.setBlockAndUpdate(centre.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
			}
		}
		Cerveau.Plan plan = new Cerveau.Plan("Cabane", Map.of("P", "minecraft:oak_planks"), List.of(List.of("PPPPP", "PPPPP", "PPPPP", "PPPPP", "PPPPP"), List.of("PPPPP", "P...P", "P...P", "P...P", "PPPPP")), 0, 0, false, 0, 0);
		Ame.Chantier chantier = Chantiers.tracer(plan, centre, level, true);
		helper.assertTrue(chantier != null, "Un terrain libre existe à côté : il doit être trouvé");
		helper.assertTrue(Math.abs(chantier.x - centre.getX()) > 3 || Math.abs(chantier.z - centre.getZ()) > 3, "L'emprise ne doit pas recouvrir la bâtisse existante");
		Ame.Chantier surPlace = Chantiers.tracer(plan, centre, level, false);
		helper.assertTrue(surPlace.x == centre.getX() && surPlace.z == centre.getZ(), "Sans recherche de terrain, on bâtit là où c'est demandé");
		for (int x = -12; x <= 12; x++) {
			for (int z = -12; z <= 12; z++) {
				for (int y = 0; y <= 1; y++) {
					level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState());
				}
			}
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void ilCreuseJusquAuFilon(final GameTestHelper helper) {
		sol(helper, 8);
		// Un massif de pierre de trois blocs de haut, avec du fer au fond.
		for (int x = 3; x < 8; x++) {
			for (int z = 0; z < 3; z++) {
				for (int y = 1; y <= 3; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
		helper.setBlock(new BlockPos(6, 1, 1), Blocks.IRON_ORE);
		Villager villageois = villageois(helper, 1, 1);
		Ame ame = Ames.de(villageois);
		Actions.lancer(villageois, ame, decision("miner", "fer", "1"), maintenant(helper));
		helper.assertTrue(Actions.activite(villageois.getUUID()) != null, "Le villageois doit se mettre à miner");
		helper.succeedWhen(() -> {
			helper.assertTrue(villageois.getInventory().hasAnyOf(java.util.Set.of(Items.RAW_IRON)), "Le fer doit finir dans ses poches ; activité=" + Actions.activite(villageois.getUUID()) + " pos=" + helper.relativePos(villageois.blockPosition()) + " notes=" + ame.nouveaux);
			helper.assertTrue(helper.getBlockState(new BlockPos(6, 1, 1)).isAir(), "Le filon doit avoir été extrait");
			helper.assertTrue(ame.nouveaux.stream().anyMatch(note -> note.startsWith("Mine :")), "Il doit savoir comment sa mine s'est terminée");
		});
	}

	@GameTest(maxTicks = 900)
	public void ilDescendEnEscalierVersUnFilonProfond(final GameTestHelper helper) {
		sol(helper, 8);
		// Un massif de pierre de cinq blocs de haut ; le villageois est dessus, le charbon tout au fond, à l'autre bout.
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 3; z++) {
				for (int y = 1; y <= 5; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
		helper.setBlock(new BlockPos(6, 1, 1), Blocks.COAL_ORE);
		Villager villageois = helper.spawn(EntityTypes.VILLAGER, new BlockPos(0, 6, 1));
		Ame ame = Ames.de(villageois);
		Actions.lancer(villageois, ame, decision("miner", "charbon", "1"), maintenant(helper));
		helper.succeedWhen(() -> {
			helper.assertTrue(villageois.getInventory().hasAnyOf(java.util.Set.of(Items.COAL)),
					"Le charbon doit finir dans ses poches ; activité=" + Actions.activite(villageois.getUUID()) + " pos=" + helper.relativePos(villageois.blockPosition()) + " notes=" + ame.nouveaux);
			helper.assertTrue(villageois.getY() < helper.absoluteVec(new Vec3(0, 5, 0)).y, "Il doit être descendu dans sa galerie");
		});
	}

	@GameTest(maxTicks = 300)
	public void ilMangeEtRangeSesPoches(final GameTestHelper helper) {
		sol(helper, 5);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST);
		Villager villageois = villageois(helper, 1, 1);
		Ame ame = Ames.de(villageois);
		villageois.getInventory().clearContent();
		villageois.getInventory().addItem(new ItemStack(Items.BREAD, 3));
		villageois.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 12));
		villageois.setHealth(10);
		Actions.lancer(villageois, ame, decision("manger", null, null), maintenant(helper));
		helper.assertTrue(villageois.getInventory().countItem(Items.BREAD) == 2, "Manger consomme un pain");
		helper.assertTrue(villageois.getHealth() > 10, "Manger soigne");
		BlockPos coffre = helper.absolutePos(new BlockPos(3, 1, 1));
		Actions.lancer(villageois, ame, decision("deposer", coord(coffre), null), maintenant(helper));
		helper.succeedWhen(() -> {
			helper.assertTrue(villageois.getInventory().isEmpty(), "Ses poches doivent être vides");
			ChestBlockEntity contenu = (ChestBlockEntity) helper.getLevel().getBlockEntity(coffre);
			helper.assertTrue(contenu != null && contenu.countItem(Items.COBBLESTONE) == 12 && contenu.countItem(Items.BREAD) == 2, "Tout doit être dans le coffre");
		});
	}

	@GameTest
	public void serviceRoleEtJournal(final GameTestHelper helper) {
		sol(helper, 5);
		Villager villageois = villageois(helper, 1, 1);
		Ame ame = Ames.de(villageois);
		ame.etranger = false;
		Ames.Village village = Ames.rattacher(villageois, ame);
		int avant = village.inscrits;
		Vie.engager(villageois, ame, "Maitre");
		helper.assertTrue("Maitre".equals(ame.maitre) && ame.suit, "Il doit suivre celui qu'il sert");
		helper.assertTrue(village.inscrits == avant + 1 && village.journal.getLast().contains("au service de Maitre"), "Le journal du village doit en garder la trace");
		helper.assertTrue(Ames.nouvelles("Lecteur") >= 1, "Un joueur qui n'a rien lu a des nouvelles à lire");
		Actions.lancer(villageois, ame, decision("rester", null, null), maintenant(helper));
		helper.assertTrue(!ame.suit, "« rester » le fait attendre sur place");
		Actions.lancer(villageois, ame, decision("suivre", "Maitre", null), maintenant(helper));
		helper.assertTrue(ame.suit, "« suivre » le remet dans les pas de son maître");
		Vie.affranchir(villageois, ame);
		helper.assertTrue(ame.maitre == null && !ame.suit, "Congédié, il ne sert plus personne");
		helper.succeed();
	}

	@GameTest
	public void leVillageoisLePlusProcheEntendLeJoueur(final GameTestHelper helper) {
		sol(helper, 8);
		Villager pres = villageois(helper, 2, 2);
		Villager loin = villageois(helper, 6, 6);
		ServerPlayer joueur = helper.makeMockServerPlayerInLevel();
		Vec3 ou = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
		joueur.teleportTo(ou.x, ou.y, ou.z);
		Ame proche = Ames.de(pres);
		Ame lointaine = Ames.de(loin);
		Evenements.entendre(joueur, "Bonjour, quelle belle journée", false);
		String nom = joueur.getName().getString();
		helper.assertTrue(proche.nouveaux.stream().anyMatch(note -> note.startsWith(nom + " t'a dit")), "Le plus proche est celui à qui l'on parle");
		helper.assertTrue(lointaine.nouveaux.stream().anyMatch(note -> note.startsWith(nom + " a dit à " + proche.nom)), "L'autre entend la conversation sans y être mêlé");
		Evenements.entendre(joueur, "Et toi, " + lointaine.nom.toUpperCase() + ", qu'en dis-tu ?", false);
		helper.assertTrue(lointaine.nouveaux.getLast().startsWith(nom + " t'a dit"), "Citer un prénom s'adresse à celui qui le porte");
		helper.succeed();
	}

	@GameTest
	public void renommerSuitLeVillageoisPartout(final GameTestHelper helper) {
		sol(helper, 5);
		Ame ame = Ames.de(villageois(helper, 1, 1));
		Ame voisin = Ames.de(villageois(helper, 3, 3));
		String ancien = ame.nom;
		voisin.ajusterRelation(ancien, 30);
		voisin.retenir(ancien + " m'a prêté sa pioche.");
		voisin.liens.add(ancien + " est ton ami d'enfance.");
		// Le monde de test survit d'un lancement à l'autre : un prénom tiré au sort ne peut pas y être déjà pris.
		StringBuilder tire = new StringBuilder("Zé");
		for (int i = 0; i < 10; i++) {
			tire.append((char) ('a' + helper.getLevel().getRandom().nextInt(26)));
		}
		String nouveau = tire.toString();
		helper.assertTrue(Ames.prenomLibre(nouveau, ame), "Le nouveau prénom doit être libre");
		Ames.renommer(ame, nouveau, null);
		helper.assertTrue(voisin.relation(nouveau) == 30 && !voisin.relations.containsKey(ancien), "L'opinion suit le nouveau prénom");
		helper.assertTrue(voisin.marquants.getLast().startsWith(nouveau) && voisin.liens.getLast().startsWith(nouveau), "Les souvenirs et les liens des autres sont réécrits");
		helper.assertTrue(!Ames.prenomLibre(nouveau, voisin), "Un prénom porté n'est plus libre pour un autre");
		helper.succeed();
	}
}
