package com.questkeeper.monster;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonsterService {

    private final MonsterRepository monsterRepository;

    public Page<Monster> searchMonsters(String name, String type, String cr, String source, Pageable pageable) {
        List<String> typeList = splitFilter(type);
        List<String> typeListLower = typeList.stream().map(String::toLowerCase).toList();
        List<String> crList = splitFilter(cr);
        List<String> sourceList = splitFilter(source);
        return monsterRepository.searchMonsters(
                name != null && name.isBlank() ? null : name,
                typeListLower.size(), typeListLower.isEmpty() ? List.of("") : typeListLower,
                crList.size(), crList.isEmpty() ? List.of("") : crList,
                sourceList.size(), sourceList.isEmpty() ? List.of("") : sourceList,
                pageable
        );
    }

    private List<String> splitFilter(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.asList(value.split(","));
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
