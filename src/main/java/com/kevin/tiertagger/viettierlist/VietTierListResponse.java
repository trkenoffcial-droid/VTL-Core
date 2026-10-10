package com.kevin.tiertagger.viettierlist;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * DTO matching the response of GET https://www.tierslist.net/api/search-player?username={name}
 */
public record VietTierListResponse(Player player) {

    public record Player(
            @SerializedName("userId") String userId,
            @SerializedName("playerName") String playerName,
            @SerializedName("rank") int rank,
            int points,
            String region,
            String title,
            @SerializedName("allTiers") List<TierEntry> allTiers
    ) {}

    public record TierEntry(
            String mode,
            String tier,
            Integer points
    ) {}
}
