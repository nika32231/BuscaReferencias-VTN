from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, field_validator


class SearchRequest(BaseModel):
    terms: list[str] = Field(min_length=1, description="Lista de términos de búsqueda normalizados o brutos.")
    poseData: dict[str, Any] | None = Field(default=None, description="Datos de pose enviados por JavaFX.")
    providers: list[str] | None = Field(default=None, description="Proveedores prioritarios para la búsqueda.")
    limit: int = Field(default=12, ge=1, le=100)
    sessionId: str | None = Field(default=None, description="Identificador opcional de sesión/caché.")

    @field_validator("terms")
    @classmethod
    def normalize_terms(cls, value: list[str]) -> list[str]:
        cleaned = [term.strip() for term in value if isinstance(term, str) and term.strip()]
        if not cleaned:
            raise ValueError("terms must contain at least one non-empty string")
        return cleaned

    @field_validator("providers")
    @classmethod
    def normalize_providers(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        cleaned = [provider.strip().lower() for provider in value if isinstance(provider, str) and provider.strip()]
        return cleaned or None


class SearchResult(BaseModel):
    thumbnailUrl: str
    sourceUrl: str
    similarity: int = Field(ge=0, le=100)
    provider: str
    title: str | None = None
    cachedPath: str | None = None


class CapabilityInfo(BaseModel):
    providers: list[str]
    cacheDir: str
    maxCacheImages: int
    onlineSearchEnabled: bool = False
    mediaPipeEnabled: bool = False
    playwrightEnabled: bool = False

