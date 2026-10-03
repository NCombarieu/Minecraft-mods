package com.ncombarieu.chronomancie.test;

import java.util.ArrayList;
import java.util.List;

import com.ncombarieu.chronomancie.ChronoConfig;
import com.ncombarieu.chronomancie.faille.Epoque;
import com.ncombarieu.chronomancie.faille.Faille;
import com.ncombarieu.chronomancie.faille.Failles;
import com.ncombarieu.chronomancie.fx.Ephemere;
import com.ncombarieu.chronomancie.item.Artefact;
import com.ncombarieu.chronomancie.item.Artefacts;
import com.ncombarieu.chronomancie.pouvoir.Echo;
import com.ncombarieu.chronomancie.pouvoir.Restauration;
import com.ncombarieu.chronomancie.pouvoir.Retour;
import com.ncombarieu.chronomancie.pouvoir.Stase;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Scénarios joués dans un vrai serveur avec un joueur simulé. */
@SuppressWarnings("removal")
public class ChronomancieTests {
	/**
	 * Pas de failles surprises pendant les tests, et des pouvoirs plus courts.
	 * Appelé au début de chaque test : le mod peut relire config/chronomancie.json après le chargement de cette classe.
	 */
	private static void reglagesDeTest() {
		ChronoConfig.get().failles.apparitionNaturelle = false;
		ChronoConfig.get().stase.duree = 2;
		ChronoConfig.get().miroir.dureeEcho = 2;
		ChronoConfig.get().failles.vagues = 2;
	}

	private static void sol(final GameTestHelper helper) {
		reglagesDeTest();
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	private static ServerPlayer joueur(final GameTestHelper helper, final Vec3 relatif) {
		ServerPlayer joueur = helper.makeMockServerPlayerInLevel();
		Vec3 pos = helper.absoluteVec(relatif);
		joueur.teleportTo(pos.x, pos.y, pos.z);
		return joueur;
	}

	@GameTest
	public void artefactsEtRecettes(final GameTestHelper helper) {
		reglagesDeTest();
		for (Artefact a : Artefact.values()) {
			helper.assertTrue(Artefacts.identifier(Artefacts.creer(a)) == a, "Artefact non reconnu : " + a.id);
		}
		helper.assertTrue(Artefacts.identifier(new ItemStack(Items.CLOCK)) == null, "Une horloge ordinaire n'est pas un artefact");

		ItemStack f = Artefacts.creer(Artefact.FRAGMENT);
		ItemStack vide = ItemStack.EMPTY;
		CraftingInput sablier = CraftingInput.of(3, 3, List.of(
				vide, f.copy(), vide,
				new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.CLOCK), new ItemStack(Items.GOLD_INGOT),
				vide, f.copy(), vide));
		ServerLevel level = helper.getLevel();
		var recette = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, sablier, level);
		helper.assertTrue(recette.isPresent(), "La recette du sablier devrait exister");
		ItemStack resultat = recette.get().value().assemble(sablier);
		helper.assertTrue(Artefacts.identifier(resultat) == Artefact.SABLIER, "Le craft doit donner un Sablier du Retour");

		// De vrais éclats d'améthyste ne doivent pas suffire
		CraftingInput triche = CraftingInput.of(3, 3, List.of(
				vide, new ItemStack(Items.AMETHYST_SHARD), vide,
				new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.CLOCK), new ItemStack(Items.GOLD_INGOT),
				vide, new ItemStack(Items.AMETHYST_SHARD), vide));
		helper.assertFalse(level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, triche, level).isPresent(),
				"Les éclats d'améthyste ordinaires ne sont pas des fragments");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void retourDansLeTemps(final GameTestHelper helper) {
		sol(helper);
		ServerPlayer joueur = joueur(helper, new Vec3(1.5, 1, 1.5));
		Vec3 depart = joueur.position();
		joueur.setHealth(16);
		// Le joueur marche le long de x pendant 40 ticks
		for (int t = 1; t <= 40; t++) {
			int tick = t;
			helper.runAfterDelay(t, () -> {
				joueur.teleportTo(depart.x + tick * 0.12, depart.y, depart.z);
				if (tick == 30) {
					joueur.setHealth(5);
				}
			});
		}
		helper.runAfterDelay(42, () -> helper.assertTrue(Retour.demarrer(joueur), "Le retour devrait démarrer"));
		helper.runAfterDelay(90, () -> {
			helper.assertFalse(Retour.enCours(joueur), "Le retour devrait être terminé");
			helper.assertTrue(joueur.position().distanceTo(depart) < 1.0, "Le joueur devrait être revenu au départ, il est à " + joueur.position());
			helper.assertTrue(joueur.getHealth() >= 15.9F, "La santé d'alors devrait être rendue (" + joueur.getHealth() + ")");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void staseFigeEtAccumule(final GameTestHelper helper) {
		sol(helper);
		ServerPlayer joueur = joueur(helper, new Vec3(1.5, 1, 1.5));
		Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, new Vec3(4.5, 1, 4.5));
		Arrow fleche = helper.spawn(EntityTypes.ARROW, new Vec3(2.5, 3, 2.5));
		fleche.setDeltaMovement(0.4, 0, 0);
		Vec3 departFleche = fleche.position();
		helper.assertTrue(Stase.demarrer(joueur), "La stase devrait démarrer");

		helper.runAfterDelay(10, () -> {
			helper.assertTrue(zombie.isNoAi(), "Le zombie devrait être figé");
			helper.assertTrue(fleche.position().distanceTo(departFleche) < 0.6, "La flèche devrait être suspendue");
			zombie.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 3);
			zombie.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 4);
			helper.assertTrue(zombie.getHealth() == zombie.getMaxHealth(), "Les dégâts devraient être mis de côté");
		});
		// Durée de la stase en test : 2 s
		helper.runAfterDelay(50, () -> {
			helper.assertFalse(zombie.isNoAi(), "Le zombie devrait être libéré");
			helper.assertTrue(zombie.getHealth() <= zombie.getMaxHealth() - 6.9F, "Les coups accumulés devraient tomber d'un bloc (" + zombie.getHealth() + ")");
			helper.assertTrue(fleche.isRemoved() || fleche.position().distanceTo(departFleche) > 0.6, "La flèche devrait repartir");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void echoRejoueEtSeBrise(final GameTestHelper helper) {
		sol(helper);
		ServerPlayer joueur = joueur(helper, new Vec3(1.5, 1, 1.5));
		Vec3 depart = joueur.position();
		for (int t = 1; t <= 30; t++) {
			int tick = t;
			helper.runAfterDelay(t, () -> joueur.teleportTo(depart.x + tick * 0.15, depart.y, depart.z + tick * 0.1));
		}
		List<Mannequin> echos = new ArrayList<>();
		helper.runAfterDelay(32, () -> {
			helper.assertTrue(Echo.demarrer(joueur), "L'écho devrait apparaître");
			echos.addAll(helper.getLevel().getEntitiesOfClass(Mannequin.class, new AABB(joueur.blockPosition()).inflate(16), Echo::estUnEcho));
			helper.assertTrue(echos.size() == 1, "Un écho attendu, trouvé " + echos.size());
			helper.assertTrue(echos.getFirst().entityTags().contains(Ephemere.TAG), "L'écho doit être marqué éphémère");
		});
		helper.runAfterDelay(45, () -> {
			Mannequin echo = echos.getFirst();
			helper.assertTrue(echo.position().distanceTo(depart) > 0.5, "L'écho devrait avoir rejoué le trajet");
			echo.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 50);
			helper.assertTrue(echo.isAlive(), "L'écho ne meurt pas sous les coups");
		});
		// 2 s de scénario + 2 s de persistance
		helper.runAfterDelay(120, () -> {
			helper.assertTrue(echos.getFirst().isRemoved(), "L'écho devrait s'être brisé");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 300)
	public void chronoscopeReparLesExplosions(final GameTestHelper helper) {
		sol(helper);
		List<BlockPos> mur = new ArrayList<>();
		for (int x = 2; x <= 5; x++) {
			for (int y = 1; y <= 4; y++) {
				BlockPos p = new BlockPos(x, y, 5);
				helper.setBlock(p, Blocks.OAK_PLANKS);
				mur.add(p);
			}
		}
		ServerPlayer joueur = joueur(helper, new Vec3(1.5, 1, 1.5));
		helper.runAfterDelay(2, () -> {
			Vec3 boum = helper.absoluteVec(new Vec3(3.5, 2.5, 5.5));
			helper.getLevel().explode(null, boum.x, boum.y, boum.z, 3.0F, Level.ExplosionInteraction.TNT);
			long detruits = mur.stream().filter(p -> helper.getBlockState(p).isAir()).count();
			helper.assertTrue(detruits > 4, "L'explosion aurait dû souffler le mur (" + detruits + ")");
			helper.assertTrue(Restauration.utiliser(joueur, false), "La restauration devrait démarrer");
		});
		helper.succeedWhen(() -> {
			for (BlockPos p : mur) {
				helper.assertBlockPresent(Blocks.OAK_PLANKS, p);
			}
		});
	}

	@GameTest(maxTicks = 900)
	public void failleSEveilleEtSEffondre(final GameTestHelper helper) {
		sol(helper);
		ServerPlayer joueur = joueur(helper, new Vec3(1.5, 1, 1.5));
		Faille faille = Failles.ouvrir(helper.getLevel(), helper.absoluteVec(new Vec3(4.5, 3, 4.5)), Epoque.GLACIAIRE);
		List<Boolean> vuDesVestiges = new ArrayList<>();
		helper.onEachTick(() -> {
			// Le joueur « combat » : chaque vestige tombe dès qu'il apparaît
			AABB zone = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16);
			for (Mob vestige : helper.getLevel().getEntitiesOfClass(Mob.class, zone, m -> m.entityTags().contains(Ephemere.TAG) && m.isAlive())) {
				vuDesVestiges.add(true);
				vestige.kill(helper.getLevel());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(faille.terminee(), "La faille n'est pas encore refermée (" + faille.etat() + ")");
			helper.assertFalse(vuDesVestiges.isEmpty(), "Des vestiges auraient dû surgir");
			AABB zone = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16);
			int fragments = helper.getLevel().getEntitiesOfClass(ItemEntity.class, zone, i -> Artefacts.est(i.getItem(), Artefact.FRAGMENT))
					.stream().mapToInt(i -> i.getItem().getCount()).sum();
			helper.assertTrue(fragments >= 3, "La faille aurait dû libérer des fragments (" + fragments + ")");
		});
	}
}
