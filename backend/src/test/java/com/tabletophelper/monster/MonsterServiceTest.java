package com.tabletophelper.monster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonsterServiceTest {

    @Mock private MonsterRepository monsterRepository;
    @InjectMocks private MonsterService monsterService;

    @Test
    @DisplayName("No filters — all params null — passes size 0 sentinel lists")
    void noFilters() {
        Page<Monster> mockPage = mock(Page.class);
        when(monsterRepository.searchMonsters(isNull(), eq(0),
                anyList(), eq(0), anyList(), eq(0), anyList(), any())).thenReturn(mockPage);

        var result = monsterService.searchMonsters(null, null, null, null, Pageable.unpaged());

        ArgumentCaptor<List<String>> typeCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> crCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> sourceCaptor = ArgumentCaptor.forClass(List.class);
        verify(monsterRepository).searchMonsters(isNull(), eq(0), typeCaptor.capture(),
                eq(0), crCaptor.capture(), eq(0), sourceCaptor.capture(), any());
        assertTrue(typeCaptor.getValue().contains(""));
        assertTrue(crCaptor.getValue().contains(""));
        assertTrue(sourceCaptor.getValue().contains(""));
    }

    @Test
    @DisplayName("Single type filter lowercased")
    void singleTypeFilter() {
        when(monsterRepository.searchMonsters(isNull(), eq(1),
                anyList(), eq(0), anyList(), eq(0), anyList(), any())).thenReturn(mock(Page.class));

        monsterService.searchMonsters(null, "Dragon", null, null, Pageable.unpaged());

        verify(monsterRepository).searchMonsters(isNull(), eq(1),
                argThat(list -> list.size() == 1 && list.contains("dragon")),
                eq(0), anyList(), eq(0), anyList(), any());
    }

    @Test
    @DisplayName("Multiple comma-separated types: Dragon,Humanoid")
    void multipleTypes() {
        when(monsterRepository.searchMonsters(any(), eq(2), anyList(),
                eq(0), anyList(), eq(0), anyList(), any())).thenReturn(mock(Page.class));

        monsterService.searchMonsters(null, "Dragon,Humanoid", null, null, Pageable.unpaged());

        verify(monsterRepository).searchMonsters(any(), eq(2),
                argThat(list -> list.size() == 2 && list.contains("dragon") && list.contains("humanoid")),
                eq(0), anyList(), eq(0), anyList(), any());
    }

    @Test
    @DisplayName("CR filter preserves exact values")
    void crFilter() {
        when(monsterRepository.searchMonsters(any(), eq(0), anyList(),
                eq(2), anyList(), eq(0), anyList(), any())).thenReturn(mock(Page.class));

        monsterService.searchMonsters(null, null, "1/4,1", null, Pageable.unpaged());

        verify(monsterRepository).searchMonsters(any(), eq(0), anyList(),
                eq(2), argThat(list -> list.contains("1/4") && list.contains("1")),
                eq(0), anyList(), any());
    }

    @Test
    @DisplayName("Blank name treated as null")
    void blankNameAsNull() {
        when(monsterRepository.searchMonsters(isNull(), eq(0), anyList(),
                eq(0), anyList(), eq(0), anyList(), any())).thenReturn(mock(Page.class));

        monsterService.searchMonsters("   ", null, null, null, Pageable.unpaged());

        verify(monsterRepository).searchMonsters(isNull(), eq(0), anyList(),
                eq(0), anyList(), eq(0), anyList(), any());
    }

    @Test
    @DisplayName("Combined filters: type + cr + source")
    void combinedFilters() {
        when(monsterRepository.searchMonsters(eq("dragon"), eq(1), anyList(),
                eq(1), anyList(), eq(1), anyList(), any())).thenReturn(mock(Page.class));

        monsterService.searchMonsters("dragon", "Beast", "5", "MM", Pageable.unpaged());

        verify(monsterRepository).searchMonsters(eq("dragon"), eq(1),
                eq(List.of("beast")), eq(1), eq(List.of("5")), eq(1), eq(List.of("MM")), any());
    }

    @Test
    @DisplayName("fuzzySearchByName with blank input returns empty list")
    void fuzzySearchBlankName() {
        var result = monsterService.fuzzySearchByName("  ", 10);
        assertTrue(result.isEmpty());
        verify(monsterRepository, never()).fuzzySearchByName(any(), anyInt());
    }

    @Test
    @DisplayName("getMonster with unknown ID throws IllegalArgumentException")
    void getMonsterUnknownId() {
        UUID id = UUID.randomUUID();
        when(monsterRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> monsterService.getMonster(id));
    }
}
