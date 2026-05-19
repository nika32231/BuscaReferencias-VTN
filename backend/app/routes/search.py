from fastapi import APIRouter, Depends

from ..models.search import CapabilityInfo, SearchRequest, SearchResult
from ..services.search_service import SearchService, get_search_service

router = APIRouter(tags=["search"])


@router.post("/search", response_model=list[SearchResult])
@router.post("/api/v1/search/references", response_model=list[SearchResult])
def search_references(
    payload: SearchRequest,
    service: SearchService = Depends(get_search_service),
) -> list[SearchResult]:
    return service.search_references(payload)


@router.get("/api/v1/capabilities", response_model=CapabilityInfo)
def capabilities(
    service: SearchService = Depends(get_search_service),
) -> CapabilityInfo:
    return service.capabilities()

