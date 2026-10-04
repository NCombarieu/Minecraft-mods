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

	private static final String[] DEBUTS = {
		"Al", "Am", "Ar", "Bal", "Ber", "Bri", "Cal", "Cor", "Dag", "Dor", "El", "Er", "Fal", "Fer", "Gal", "Gon", "Hal", "Hé", "Il", "Is",
		"Jo", "Ker", "Lan", "Lu", "Mal", "Mer", "Nan", "No", "Od", "Or", "Per", "Ro", "Sa", "Sy", "Tan", "Thé", "Ul", "Va", "Wil", "Yv", "Zé"
	};
	private static final String[] MILIEUX = {"a", "e", "i", "o", "an", "ar", "el", "en", "ér", "il", "in", "ol", "or", "ui", "", ""};
	private static final String[] FINS_F = {"a", "ane", "elle", "ène", "ette", "ia", "ie", "ine", "ise", "ora", "wen", "ys"};
	private static final String[] FINS_M = {"ard", "as", "bert", "eau", "ic", "ien", "in", "mond", "o", "on", "ot", "ric"};
	/** Mots tirés au sort et glissés dans les demandes d'invention : sans eux, le modèle retombe sur les mêmes idées. */
	private static final String[] GERMES = {
		"brume", "dette", "cloche", "sel", "renard", "exil", "miel", "foudre", "serment", "four", "jumeau", "rouille", "carte", "fièvre", "moisson",
		"corde", "héritage", "source", "masque", "cendre", "pari", "naufrage", "chanson", "puits", "frontière", "veillée", "os", "marché", "silence", "loup",
		"gel", "tambour", "relique", "verger", "orphelin", "enclume", "marée", "rumeur", "lanterne", "tricherie", "pèlerin", "ruche", "éboulement", "noces", "épice",
		"charbon", "prophétie", "barque", "vendetta", "champignon", "comète", "taupe", "duel", "grenier", "cicatrice", "troc", "ermite", "pont", "corbeau", "fête"
	};

	private static final Map<UUID, Ame> AMES = new LinkedHashMap<>();
	private static final List<Village> VILLAGES = new ArrayList<>();
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
		List<Village> villages = new ArrayList<>();
	}

	/** Un village et sa couleur propre (inventée par Claude) : ses habitants en héritent prénoms, parler et coutumes. */
	public static final class Village {
		public int id;
		public String nom;
		/** Type de village d'après ses habitants : plains, desert, savanna, taiga, snow, swamp, jungle. */
		public String type;
		/** Vide si l'invention a échoué : les habitants naissent alors sans elle. */
		public String culture;
		public int x;
		public int z;
		transient boolean enCours;
		transient int echecs;
	}

	static final int RAYON_VILLAGE = 128;
	public static final String TAG_ETRANGER = "hameau.etranger";

	static List<Village> villages() {
		return Collections.unmodifiableList(VILLAGES);
	}

	static Village village(final Ame ame) {
		if (ame.village != null) {
			for (Village village : VILLAGES) {
				if (village.id == ame.village) {
					return village;
				}
			}
		}
		return null;
	}

	/** Le village fondé le plus proche de ce point, ou null s'il n'y en a pas à portée. */
	static Village villageVers(final int x, final int z) {
		Village proche = null;
		long meilleure = Long.MAX_VALUE;
		for (Village village : VILLAGES) {
			long dx = village.x - x;
			long dz = village.z - z;
			if (Math.abs(dx) < RAYON_VILLAGE && Math.abs(dz) < RAYON_VILLAGE && dx * dx + dz * dz < meilleure) {
				meilleure = dx * dx + dz * dz;
				proche = village;
			}
		}
		return proche;
	}

	/** Rattache l'âme au village où elle se trouve ; le fonde (encore sans nom ni culture) s'il n'existe pas. */
	static Village rattacher(final Villager villageois, final Ame ame) {
		Village village = village(ame);
		if (village == null) {
			village = villageVers(villageois.getBlockX(), villageois.getBlockZ());
		}
		if (village == null) {
			village = new Village();
			village.id = VILLAGES.stream().mapToInt(v -> v.id).max().orElse(0) + 1;
			village.x = villageois.getBlockX();
			village.z = villageois.getBlockZ();
			village.type = villageois.getVillagerData().type().unwrapKey().map(cle -> cle.identifier().getPath()).orElse("plains");
			VILLAGES.add(village);
		}
		ame.village = village.id;
		return village;
	}

	static String germes(final int combien) {
		List<String> tires = new ArrayList<>();
		while (tires.size() < combien) {
			String mot = auHasard(GERMES);
			if (!tires.contains(mot)) {
				tires.add(mot);
			}
		}
		return String.join(", ", tires);
	}

	static char initiale() {
		return "ABCDEFGHIJKLMNOPRSTUVYZ".charAt(HASARD.nextInt(23));
	}

	static int age() {
		return 17 + HASARD.nextInt(60);
	}

	/** Prénom provisoire (ou définitif sans clé API), assemblé syllabe par syllabe. */
	private static String prenom(final boolean femme) {
		for (int essai = 0; essai < 40; essai++) {
			String nom = auHasard(DEBUTS) + auHasard(MILIEUX) + auHasard(femme ? FINS_F : FINS_M);
			if (nom.length() <= 11 && parNom(nom) == null && !nom.matches(".*[aeiouéèy]{3}.*")) {
				return nom;
			}
		}
		return auHasard(DEBUTS) + auHasard(femme ? FINS_F : FINS_M) + "-" + (AMES.size() + 1);
	}

	/** Un prénom utilisable : un seul mot (les commandes le lisent ainsi), et pas déjà porté. */
	static boolean prenomLibre(final String nom, final Ame pour) {
		if (nom == null || !nom.matches("[\\p{L}][\\p{L}-]{1,15}")) {
			return false;
		}
		Ame porteur = parNom(nom);
		return porteur == null || porteur == pour;
	}

	/** Change le prénom partout : sur le villageois, dans les opinions et dans les souvenirs des autres. */
	static void renommer(final Ame ame, final String nouveau, final Villager villageois) {
		String ancien = ame.nom;
		ame.nom = nouveau;
		if (villageois != null) {
			villageois.setCustomName(Component.literal(nouveau).withStyle(ChatFormatting.YELLOW));
			Corps.nommer(villageois, nouveau);
		}
		if (ancien == null || ancien.equals(nouveau)) {
			return;
		}
		java.util.regex.Pattern motif = java.util.regex.Pattern.compile("(?<![\\p{L}-])" + java.util.regex.Pattern.quote(ancien) + "(?![\\p{L}-])");
		String remplacement = java.util.regex.Matcher.quoteReplacement(nouveau);
		for (Ame autre : AMES.values()) {
			Integer opinion = autre.relations.remove(ancien);
			if (opinion != null && autre != ame) {
				autre.relations.put(nouveau, opinion);
			}
			for (List<String> textes : List.of(autre.liens, autre.marquants, autre.recents, autre.nouveaux)) {
				textes.replaceAll(t -> motif.matcher(t).replaceAll(remplacement));
			}
			if (autre.projet != null) {
				autre.projet = motif.matcher(autre.projet).replaceAll(remplacement);
			}
		}
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
		if (!ame.lie && !ame.ebauche && !ame.etranger && AMES.size() > 1) {
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
			ame.baptise = true;
			ame.femme = List.of(PRENOMS_F).contains(porte) || (!List.of(PRENOMS_M).contains(porte) && ame.femme);
		} else {
			ame.nom = prenom(ame.femme);
		}
		// Ce qui suit n'est qu'un brouillon : Claude invente la vraie personnalité dès que le villageois s'éveille (Vie.naitre).
		ame.ebauche = true;
		ame.etranger = villageois.entityTags().contains(TAG_ETRANGER);
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
	static void lier(final Ame ame) {
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
		VILLAGES.clear();
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
				if (lue.villages != null) {
					VILLAGES.addAll(lue.villages);
				}
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
		sauvegarde.villages.addAll(VILLAGES);
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
