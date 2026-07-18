package com.questkeeper.monster;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonsterService {

    private final MonsterRepository monsterRepository;

    public Page<Monster> searchMonsters(String name, String type, String cr, String source, Pageable pageable) {
        return monsterRepository.searchMonsters(
                name != null && name.isBlank() ? null : name,
                type != null && type.isBlank() ? null : type,
                cr != null && cr.isBlank() ? null : cr,
                source != null && source.isBlank() ? null : source,
                pageable
        );
    }

    public Monster getMonster(UUID id) {
        return monsterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Monster not found"));
    }

    public List<String> getSources() {
        return monsterRepository.findDistinctSources();
    }

    public List<String> getTypes() {
        return monsterRepository.findDistinctTypes();
    }

    public List<String> getChallengeRatings() {
        return monsterRepository.findDistinctChallengeRatings();
    }
}
