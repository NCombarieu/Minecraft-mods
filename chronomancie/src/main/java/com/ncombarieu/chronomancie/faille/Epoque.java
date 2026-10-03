package com.ncombarieu.chronomancie.faille;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Chaque faille donne sur une époque différente, avec ses créatures, ses couleurs et ses trésors. */
public enum Epoque {
	GLACIAIRE("glaciaire", "Ère Glaciaire", "de l'Ère Glaciaire", "Le froid d'un monde oublié s'engouffre par la brèche.", ChatFormatting.AQUA, 0x8FD9FF,
			List.of(EntityTypes.STRAY, EntityTypes.STRAY, EntityTypes.ZOMBIE),
			List.of(Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW_BLOCK, Blocks.ICE),
			ParticleTypes.SNOWFLAKE,
			List.of(Items.BLUE_ICE, Items.PACKED_ICE, Items.SNOWBALL)),
	PHARAONS("pharaons", "Âge des Pharaons", "de l'Âge des Pharaons", "Le sable d'un empire englouti tourbillonne dans la faille.", ChatFormatting.GOLD, 0xFFC94C,
			List.of(EntityTypes.HUSK, EntityTypes.HUSK, EntityTypes.PARCHED),
			List.of(Blocks.CHISELED_SANDSTONE, Blocks.GOLD_BLOCK, Blocks.CUT_SANDSTONE, Blocks.RAW_GOLD_BLOCK),
			ParticleTypes.WAX_ON,
			List.of(Items.GOLD_INGOT, Items.GOLD_NUGGET, Items.EMERALD)),
	ILLUSIONS("illusions", "Ère des Illusions", "de l'Ère des Illusions", "Une époque qui n'a jamais existé tente de forcer le passage.", ChatFormatting.DARK_GREEN, 0x3FD87A,
			List.of(EntityTypes.VINDICATOR, EntityTypes.PILLAGER, EntityTypes.ILLUSIONER),
			List.of(Blocks.EMERALD_BLOCK, Blocks.DARK_OAK_PLANKS, Blocks.CRYING_OBSIDIAN, Blocks.MOSSY_COBBLESTONE),
			ParticleTypes.WITCH,
			List.of(Items.EMERALD, Items.ARROW, Items.EXPERIENCE_BOTTLE)),
	FUTUR("futur", "Futur Lointain", "du Futur Lointain", "Des échos d'un avenir stérile suintent de la brèche.", ChatFormatting.LIGHT_PURPLE, 0xC77DFF,
			List.of(EntityTypes.ENDERMITE, EntityTypes.VEX, EntityTypes.ENDERMITE, EntityTypes.VEX),
			List.of(Blocks.PURPUR_BLOCK, Blocks.END_STONE_BRICKS, Blocks.OBSIDIAN, Blocks.AMETHYST_BLOCK),
			ParticleTypes.END_ROD,
			List.of(Items.ENDER_PEARL, Items.CHORUS_FRUIT, Items.AMETHYST_SHARD));

	public final String id;
	public final String nom;
	/** « de l'Ère Glaciaire », « du Futur Lointain »… */
	public final String complement;
	public final String presage;
	public final ChatFormatting couleur;
	public final int rgb;
	public final List<EntityType<?>> vestiges;
	public final List<Block> palette;
	public final ParticleOptions particule;
	public final List<Item> tresors;

	Epoque(final String id, final String nom, final String complement, final String presage, final ChatFormatting couleur, final int rgb, final List<EntityType<?>> vestiges,
			final List<Block> palette, final ParticleOptions particule, final List<Item> tresors) {
		this.id = id;
		this.nom = nom;
		this.complement = complement;
		this.presage = presage;
		this.couleur = couleur;
		this.rgb = rgb;
		this.vestiges = vestiges;
		this.palette = palette;
		this.particule = particule;
		this.tresors = tresors;
	}

	public static Epoque parId(final String id) {
		for (Epoque e : values()) {
			if (e.id.equals(id)) {
				return e;
			}
		}
		return null;
	}
}
