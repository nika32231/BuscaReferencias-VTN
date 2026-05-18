from fastapi import APIRouter, Depends

from app.models.search import CapabilityInfo, SearchRequest, SearchResult
from app.services.search_service import SearchService, get_search_service

router = APIRouter(prefix="/api/v1", tags=["search"])


@router.post("/search/references", response_model=list[SearchResult])
def search_references(
    payload: SearchRequest,
    service: SearchService = Depends(get_search_service),
) -> list[SearchResult]:
    return service.search_references(payload)


@router.get("/capabilities", response_model=CapabilityInfo)
def capabilities(
    service: SearchService = Depends(get_search_service),
) -> CapabilityInfo:
    return service.capabilities()

