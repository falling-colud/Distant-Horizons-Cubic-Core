/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.api.interfaces.override.worldGenerator;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.methods.cubic.DhApiCubic;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Generates distant terrain for a cubic level (see {@link DhApiCubic}): every request is one cubic
 * LOD section, a 64 x 64 column area of limited height. <br><br>
 *
 * {@link #getReturnType()} must return {@link EDhApiWorldGeneratorReturnType#API_DATA_SOURCES}.
 * Requests come for detail levels between {@link #getSmallestDataDetailLevel()} and
 * {@link #getLargestDataDetailLevel()}.
 *
 * @since API 7.2.0 (cubic)
 */
public interface IDhApiCubicWorldGenerator extends IDhApiWorldGenerator
{
	/**
	 * Generates one cubic LOD section. <br><br>
	 *
	 * The section covers blocks {@code [minBlockX, minBlockX + 64 * 2^detailLevel)} on X and Z
	 * (starting at the chunk positions given) and {@code [minBlockY, minBlockY + heightInBlocks)} on Y.
	 * Every column set on the data source must cover that whole height, top to bottom, with
	 * data point heights relative to {@code minBlockY} (so 0 is the section's bottom): air included,
	 * since the air's sky light lights the faces below it. <br><br>
	 *
	 * Data points hold the sky light of the space directly above their top, so a section needs
	 * to know what lies above it (a roof high above is dark below, open sky is lit).
	 *
	 * @param chunkPosMinX the lowest chunk X the section covers
	 * @param chunkPosMinZ the lowest chunk Z the section covers
	 * @param minBlockY the lowest block Y the section covers
	 * @param heightInBlocks how tall the section is
	 * @param lodPosX the section's X index at its detail level
	 * @param lodPosY the section's Y index: {@code minBlockY / heightInBlocks}
	 * @param lodPosZ the section's Z index at its detail level
	 * @param detailLevel the data detail level: 0 means each column is one block wide, 1 two blocks, etc.
	 * @param pooledFullDataSource the data source to fill, 64 x 64 columns
	 * @param generatorMode how far generation should go
	 * @param worldGeneratorThreadPool the thread pool generation should run on
	 * @param resultConsumer can be ignored, completion is tracked through the returned future
	 */
	CompletableFuture<Void> generateCubicLod(
		int chunkPosMinX, int chunkPosMinZ,
		int minBlockY, int heightInBlocks,
		int lodPosX, int lodPosY, int lodPosZ, byte detailLevel,
		IDhApiFullDataSource pooledFullDataSource,
		EDhApiDistantGeneratorMode generatorMode,
		ExecutorService worldGeneratorThreadPool,
		Consumer<IDhApiFullDataSource> resultConsumer
		);

}
