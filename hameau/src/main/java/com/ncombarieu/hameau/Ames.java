package com.ncombarieu.hameau;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

/** Registre des âmes, sauvegardé dans le dossier du monde (hameau/ames.json). */
public final class Ames {
	private static final String[] PRENOMS_F = {
		"Maëlle", "Clotilde", "Ysaline", "Brunehilde", "Délia", "Faustine", "Hermance", "Jacinthe", "Mathurine", "Odile",
		"Quitterie", "Sidonie", "Ursule", "Aliénor", "Capucine", "Églantine", "Guillemette", "Iseult", "Léonie", "Ninon"
	};
	private static final String[] PRENOMS_M = {
		"Bastien", "Gaspard", "Anselme", "Côme", "Edmond", "Gauvain", "Isidore", "Léandre", "Norbert", "Perrin",
		"Raoul", "Théodule", "Victorin", "Barnabé", "Donatien", "Firmin", "Honoré", "Josselin", "Marceau", "Octave"
	};
	private static final String[] QUALITES = {
		"généreux", "jovial", "curieux de tout", "loyal", "tendre", "courageux", "drôle", "rêveur", "patient", "franc",
		"débrouillard", "chaleureux", "malicieux", "obstiné quand il a une idée en tête", "protecteur", "enthousiaste", "romantique", "serviable"
	};
	private static final String[] DEFAUTS = {
		"têtu", "bavard", "orgueilleux", "peureux", "rancunier", "avare", "colérique", "paresseux", "jaloux", "menteur à ses heures",
		"superstitieux", "envieux", "commère", "étourdi", "susceptible", "joueur invétéré", "gourmand", "vantard"
	};
	private static final String[] MANIES = {
		"collectionne les cailloux de forme étrange", "fredonne sans arrêt le même air", "cite des proverbes qu'il invente", "parle à ses outils",
		"compte tout ce qu'il voit", "donne des surnoms à tout le monde", "prédit le temps d'après ses genoux", "parie sur n'importe quoi",
		"raconte toujours la même histoire de jeunesse", "renifle tout ce qu'on lui tend avant de le prendre", "rêve d'inventer une machine volante",
		"compose des poèmes maladroits", "craint les poules", "garde un journal de tous les potins", "veut goûter tout ce qui se mange",
		"se croit descendant d'un roi", "imite les cris des animaux", "ne supporte pas le désordre", "adore les devinettes", "se vante d'exploits invérifiables"
	};
	private static final String[] PARLERS = {
		"parle peu, par phrases sèches", "s'exprime avec emphase, comme au théâtre", "use d'un patois rustique et d'images de la terre",
		"plaisante sans cesse, pince-sans-rire", "parle vite et saute du coq à l'âne", "est doux et hésitant, cherche ses mots",
		"jure comme un charretier", "pose question sur question", "parle en donneur de leçons", "chuchote volontiers des confidences",
		"s'enflamme pour un rien", "est d'une politesse exagérée"
	};
	private static final String[] DESIRS = {
		"organiser la plus belle fête que le village ait connue", "amasser assez d'émeraudes pour ne plus jamais manquer", "trouver un ami véritable",
		"découvrir ce qu'il y a au-delà du village", "devenir le meilleur dans son métier", "trouver l'amour", "bâtir quelque chose qui lui survivra",
		"apprendre les secrets des voyageurs qui passent", "devenir chef du village", "voir la mer un jour", "réunir une collection d'objets venus d'ailleurs",
		"gagner enfin un pari contre son voisin", "écrire la chronique du village", "qu'on raconte un jour ses exploits", "réconcilier tout le monde"
	};
	private static final String[] PEURS = {
		"la nuit et ce qui rôde dehors", "mourir oublié de tous", "qu'on lui vole ce qu'il possède", "être la risée du village", "les pillards",
		"la solitude", "qu'on découvre un vieux secret", "la faim", "l'orage", "décevoir ceux qu'il aime", "vieillir sans avoir rien vu du monde"
	};
	/** Liens anciens : {ce que sait le premier, son opinion, ce que sait le second, son opinion}. %s = le nom de l'autre. */
	private static final Object[][] LIENS = {
		{"%s est ton ami d'enfance, vous avez fait les quatre cents coups ensemble.", 35, "%s est ton ami d'enfance, vous avez fait les quatre cents coups ensemble.", 35},
		{"%s et toi êtes rivaux depuis toujours : chacun veut prouver qu'il vaut mieux que l'autre.", -20, "%s et toi êtes rivaux depuis toujours : chacun veut prouver qu'il vaut mieux que l'autre.", -20},
		{"Tu es amoureux de %s en secret et tu n'as jamais osé le lui dire.", 50, null, 5},
		{"Tu dois cinq émeraudes à %s depuis des mois et tu évites le sujet.", 5, "%s te doit cinq émeraudes depuis des mois.", -10},
		{"%s est de ta famille, vous vous chamaillez mais vous vous soutenez.", 40, "%s est de ta famille, vous vous chamaillez mais vous vous soutenez.", 40},
		{"Tu admires %s et tu cherches son approbation.", 30, "%s te suit partout et boit tes paroles, ce qui te flatte et t'agace à la fois.", 10},
		{"%s et toi ne vous parlez plus depuis une vieille dispute dont plus personne ne sait la cause.", -30, "%s et toi ne vous parlez plus depuis une vieille dispute dont plus personne ne sait la cause.", -30},
		{"%s t'a sauvé la vie un soir d'orage, tu lui en es redevable.", 45, "Tu as sauvé la vie de %s un soir d'orage, et il ne cesse de te le rappeler.", 15},
		{"%s et toi partagez un secret : une cachette que vous seuls connaissez.", 25, "%s et toi partagez un secret : une cachette que vous seuls connaissez.", 25},
		{"Tu soupçonnes %s de tricher aux paris du village.", -15, null, 0}
	};

	private static final Map<UUID, Ame> AMES = new LinkedHashMap<>();
	private static final Random HASARD = new Random();
	static Budget budget = new Budget();
	/** Chunks maintenus chargés pour la vie hors ligne (monde principal). */
	static Set<Long> chunksForces = new HashSet<>();
	private static Path dossier;

	private Ames() {
	}

	private static final class Sauvegarde {
		List<Ame> ames = new ArrayList<>();
		Budget budget = new Budget();
		Set<Long> chunksForces = new HashSet<>();
	}

	public static Collection<Ame> toutes() {
		return Collections.unmodifiableCollection(AMES.values());
	}

	public static Ame connue(final UUID uuid) {
		return AMES.get(uuid);
	}

	/** Compare sans tenir compte de la casse ni des accents : « maelle » désigne Maëlle. */
	static String simplifier(final String texte) {
		return java.text.Normalizer.normalize(texte, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().trim();
	}

	public static Ame parNom(final String nom) {
		String cherche = simplifier(nom);
		for (Ame ame : AMES.values()) {
			if (simplifier(ame.nom).equals(cherche)) {
				return ame;
			}
		}
		return null;
	}

	private static <T> T auHasard(final T[] choix) {
		return choix[HASARD.nextInt(choix.length)];
	}

	/** L'âme d'un villageois ; créée (nom, caractère, liens, petit bagage) la première fois qu'on le rencontre. */
	public static Ame de(final Villager villageois) {
		Ame ame = AMES.get(villageois.getUUID());
		if (ame == null) {
			ame = creer(villageois);
			AMES.put(villageois.getUUID(), ame);
		}
		if (ame.manie == null) {
			completer(ame);
		}
		if (!ame.lie && AMES.size() > 1) {
			lier(ame);
		}
		ame.enfant = villageois.isBaby();
		// Sans corps humain (enfants), le prénom reste lisible au-dessus de la tête.
		villageois.setCustomNameVisible(ame.enfant && !HameauConfig.get().corpsEnfants);
		if (villageois.getCustomName() == null) {
			villageois.setCustomName(Component.literal(ame.nom).withStyle(ChatFormatting.YELLOW));
		}
		return ame;
	}

	private static Ame creer(final Villager villageois) {
		Ame ame = new Ame();
		ame.uuid = villageois.getUUID().toString();
		ame.femme = HASARD.nextBoolean();
		// Un villageois déjà baptisé (par une version précédente ou par un joueur) garde son nom.
		String porte = villageois.getCustomName() != null ? villageois.getCustomName().getString().trim() : "";
		if (!porte.isEmpty() && !porte.contains(" ") && parNom(porte) == null) {
			ame.nom = porte;
			ame.femme = List.of(PRENOMS_F).contains(porte) || (!List.of(PRENOMS_M).contains(porte) && ame.femme);
		} else {
			List<String> libres = new ArrayList<>(List.of(ame.femme ? PRENOMS_F : PRENOMS_M));
			for (Ame autre : AMES.values()) {
				libres.remove(autre.nom);
			}
			ame.nom = libres.isEmpty() ? auHasard(ame.femme ? PRENOMS_F : PRENOMS_M) + "-" + (AMES.size() + 1) : libres.get(HASARD.nextInt(libres.size()));
		}
		ame.traits.add(auHasard(QUALITES));
		String seconde = auHasard(QUALITES);
		if (HASARD.nextBoolean() && !ame.traits.contains(seconde)) {
			ame.traits.add(seconde);
		}
		ame.traits.add(auHasard(DEFAUTS));
		ame.desir = auHasard(DESIRS);
		ame.peur = auHasard(PEURS);
		ame.metier = Perception.metier(villageois);
		ame.x = villageois.getBlockX();
		ame.y = villageois.getBlockY();
		ame.z = villageois.getBlockZ();
		villageois.getInventory().addItem(new ItemStack(Items.EMERALD, 2));
		villageois.getInventory().addItem(new ItemStack(bagage(Perception.cleMetier(villageois)), 2));
		completer(ame);
		return ame;
	}

	/** Ajoute ce qui manque aux âmes nées avant que ces traits existent. */
	private static void completer(final Ame ame) {
		if (List.of(PRENOMS_F).contains(ame.nom)) {
			ame.femme = true;
		}
		ame.manie = auHasard(MANIES);
		ame.parler = auHasard(PARLERS);
		ame.voix = 0.75F + HASARD.nextFloat() * 0.5F + (ame.femme ? 0.15F : 0F);
		if ("les étrangers armés".equals(ame.peur)) {
			ame.peur = auHasard(PEURS);
		}
		boolean aQualite = false;
		for (String trait : ame.traits) {
			aQualite |= List.of(QUALITES).contains(trait);
		}
		if (!aQualite) {
			ame.traits.set(0, auHasard(QUALITES));
		}
	}

	/** Donne à l'âme un lien ancien avec un habitant du même village : de quoi nourrir des histoires. */
	private static void lier(final Ame ame) {
		List<Ame> voisins = new ArrayList<>();
		for (Ame autre : AMES.values()) {
			if (autre != ame && Math.abs(autre.x - ame.x) < 96 && Math.abs(autre.z - ame.z) < 96 && !ame.relations.containsKey(autre.nom)) {
				voisins.add(autre);
			}
		}
		if (voisins.isEmpty()) {
			return;
		}
		ame.lie = true;
		Ame autre = voisins.get(HASARD.nextInt(voisins.size()));
		Object[] lien = auHasard(LIENS);
		ame.liens.add(String.format((String) lien[0], autre.nom));
		ame.ajusterRelation(autre.nom, (Integer) lien[1]);
		if (lien[2] != null) {
			autre.liens.add(String.format((String) lien[2], ame.nom));
		}
		autre.ajusterRelation(ame.nom, (Integer) lien[3]);
	}

	private static Item bagage(final String metier) {
		return switch (metier) {
			case "farmer" -> Items.BREAD;
			case "fisherman" -> Items.COOKED_COD;
			case "librarian" -> Items.BOOK;
			case "armorer", "toolsmith", "weaponsmith" -> Items.IRON_INGOT;
			case "cleric" -> Items.REDSTONE;
			case "butcher" -> Items.COOKED_BEEF;
			case "shepherd" -> Items.SHEARS;
			case "fletcher" -> Items.ARROW;
			case "mason" -> Items.BRICKS;
			case "leatherworker" -> Items.LEATHER;
			case "cartographer" -> Items.PAPER;
			default -> Items.APPLE;
		};
	}

	public static void oublier(final UUID uuid) {
		AMES.remove(uuid);
	}

	public static void charger(final MinecraftServer server) {
		AMES.clear();
		budget = new Budget();
		chunksForces = new HashSet<>();
		dossier = server.getWorldPath(LevelResource.ROOT).resolve("hameau");
		Path fichier = dossier.resolve("ames.json");
		if (!Files.exists(fichier)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(fichier, StandardCharsets.UTF_8)) {
			Sauvegarde lue = HameauConfig.GSON.fromJson(reader, Sauvegarde.class);
			if (lue != null) {
				for (Ame ame : lue.ames) {
					if (ame.liens == null) {
						ame.liens = new ArrayList<>();
					}
					AMES.put(UUID.fromString(ame.uuid), ame);
				}
				budget = lue.budget != null ? lue.budget : new Budget();
				chunksForces = lue.chunksForces != null ? lue.chunksForces : new HashSet<>();
			}
			Hameau.LOGGER.info("Hameau : {} âmes retrouvées. {}", AMES.size(), budget.resume());
		} catch (IOException | RuntimeException e) {
			Hameau.LOGGER.error("Impossible de lire {}", fichier, e);
		}
	}

	public static void sauvegarder() {
		if (dossier == null) {
			return;
		}
		Sauvegarde sauvegarde = new Sauvegarde();
		sauvegarde.ames.addAll(AMES.values());
		sauvegarde.budget = budget;
		sauvegarde.chunksForces = chunksForces;
		try {
			Files.createDirectories(dossier);
			Path temporaire = dossier.resolve("ames.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temporaire, StandardCharsets.UTF_8)) {
				HameauConfig.GSON.toJson(sauvegarde, writer);
			}
			Files.move(temporaire, dossier.resolve("ames.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Hameau.LOGGER.error("Impossible de sauvegarder les âmes", e);
		}
	}
}
