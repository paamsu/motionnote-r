"""Authored scenario details, not OCR/model predictions or live observations."""


def context_for(name):
    def actor(plate, brand, model, color, relationship):
        return {
            "id": "vehicle-1",
            "plate": plate,
            "brand": brand,
            "model": model,
            "color": color,
            "source": "scenario_fixture",
            "recognition_status": "not_run",
            "relationship": relationship,
        }

    own = {
        "plate": "DEMO-OWN-01",
        "brand": "Hyundai",
        "model": "아이오닉 6",
        "color": "흰색",
        "source": "scenario_fixture",
    }
    common = {
        "source": "scenario_fixture",
        "own_vehicle": own,
        "location_label": "가상 시험 구간",
        "capture": {
            "camera": "rear" if name == "parking" else "front",
            "trigger_seconds": 10,
            "sensor_window_seconds": 30,
        },
        "actors": [],
        "facts": [],
        "unknowns": ["실차 자료 미연결"],
    }
    details = {
        "parking": {
            "location_label": "테스트 주차장 · B2 · 24번 기둥",
            "parking": {
                "floor": "B2",
                "landmark": "24번 기둥",
                "bay": None,
                "position_source": "authored_scenario",
            },
            "impact": {
                "area": "우측 후방",
                "area_source": "scenario_fixture",
                "contact_confirmed": False,
            },
            "actors": [
                {
                    "id": "vehicle-1",
                    "plate": "DEMO 4821",
                    "brand": "Hyundai",
                    "model": "쏘나타",
                    "body": "중형 세단",
                    "color": "은색",
                    "source": "scenario_fixture",
                    "recognition_status": "not_run",
                    "relationship": "충격 시각 주변 차량 · 가해 여부 미확정",
                }
            ],
            "facts": [
                {"label": "차량 상태", "value": "P단 · 0 km/h"},
                {"label": "충격 위치", "value": "우측 후방 (가상 설정)"},
                {"label": "주변 차량", "value": "은색 세단 1대"},
            ],
            "unknowns": [
                "번호판·차종은 가상 설정이며 인식 결과가 아님",
                "접촉 및 책임 차량 미확정",
            ],
        },
        "crash": {
            "impact": {
                "area": "전면 우측",
                "area_source": "scenario_fixture",
                "contact_confirmed": False,
            },
            "actors": [
                actor(
                    "DEMO 7352",
                    "Kia",
                    "쏘렌토",
                    "회색",
                    "충돌 시각 주변 차량 · 접촉 관계 미확정",
                )
            ],
            "facts": [
                {"label": "에어백", "value": "전개 신호 (가상)"},
                {"label": "충격 위치", "value": "전면 우측 (가상 설정)"},
                {"label": "보험 접수", "value": "본인 차량 계약 기준"},
            ],
            "unknowns": ["충돌 책임 미판정", "주변 차량 번호·차종은 가상 설정"],
        },
        "pothole": {
            "facts": [
                {"label": "도로 표면", "value": "패임 (가상)"},
                {"label": "주행 상태", "value": "42 km/h"},
                {"label": "접수 단계", "value": "운전자 확인 필요"},
            ],
            "unknowns": ["차선 위치 미확인", "실제 도로 영상 없음"],
        },
        "rain": {
            "facts": [
                {"label": "와이퍼", "value": "고속 작동"},
                {"label": "시야", "value": "저하 신호"},
                {"label": "노면", "value": "우천 가상 설정"},
            ]
        },
        "diagnosis": {
            "facts": [
                {"label": "비교 조건", "value": "1,200 rpm · 22°C"},
                {"label": "측정 신호", "value": "전압 저하"},
                {"label": "후속 확인", "value": "동일 조건 재측정"},
            ]
        },
        "emergency": {
            "facts": [
                {"label": "요청 방식", "value": "무응답 가상 신호에 따른 자동 요청"},
                {"label": "수신처", "value": "119 모의 수신처"},
            ],
            "unknowns": ["의학적 원인 미판정"],
        },
        "violation": {
            "actors": [
                actor(
                    "DEMO 6210",
                    "Hyundai",
                    "스타리아",
                    "검정",
                    "보호구역에 정차한 관측 차량",
                )
            ],
            "facts": [
                {"label": "구역", "value": "보호구역 (가상)"},
                {"label": "관측 차량 정지", "value": "180초"},
                {"label": "판단", "value": "신고 후보 · 확인 필요"},
            ],
            "unknowns": [
                "시간대·표지·예외 사유 확인 필요",
                "번호·차종·정지 시간은 가상 관측 설정",
            ],
        },
        "reckless": {
            "actors": [
                actor(
                    "DEMO 9134", "Kia", "K5", "파랑", "급격한 주행 변화가 관측된 차량"
                )
            ],
            "facts": [
                {"label": "관측 차량 변화", "value": "급격한 조작 4회 (가상)"},
                {"label": "판단", "value": "회피 운전 여부 확인 필요"},
            ],
            "unknowns": [
                "주행 의도·책임 미판정",
                "주변 차량 조작 횟수는 영상 분석 결과가 아님",
            ],
        },
        "wanted": {
            "actors": [
                actor(
                    "DEMO-001",
                    "Hyundai",
                    "아반떼",
                    "흰색",
                    "가상 인가 목록과 번호가 일치한 후보",
                )
            ],
            "facts": [
                {"label": "대조 목록", "value": "인가 목록 시험 데이터"},
                {"label": "판단", "value": "가상 일치 후보"},
            ],
            "unknowns": ["실제 수배 정보 조회 없음", "번호판 인식·경찰 확인 미실행"],
        },
        "speed_bump": {
            "facts": [
                {"label": "도로 표면", "value": "과속방지턱 비교 사례"},
                {"label": "신호 형태", "value": "완만한 충격 1회"},
                {"label": "판단", "value": "도로 파손 접수 대상 아님"},
            ]
        },
        "washer": {
            "facts": [
                {"label": "와이퍼", "value": "고속 작동"},
                {"label": "워셔액", "value": "분사 중 (가상)"},
                {"label": "시야 저하", "value": "감지되지 않음"},
            ]
        },
        "healthy": {
            "facts": [
                {"label": "전압", "value": "13.9 V 부근 (가상)"},
                {"label": "비교 조건", "value": "1,200 rpm · 22°C"},
                {"label": "판단 범위", "value": "관측 전압 신호의 비교 자료"},
            ],
            "unknowns": ["차량 전체 정상 판정 아님"],
        },
        "help_119": {
            "facts": [
                {"label": "요청 방식", "value": "운전자가 도움 요청"},
                {"label": "수신처", "value": "119 모의 수신처"},
            ],
            "unknowns": ["실제 119 연결 없음", "증상·부상 정도 미확인"],
        },
        "help_112": {
            "facts": [
                {"label": "요청 방식", "value": "운전자가 도움 요청"},
                {"label": "수신처", "value": "112 모의 수신처"},
            ],
            "unknowns": ["실제 112 연결 없음", "사건 종류·현장 상태 미확인"],
        },
    }
    common.update(details.get(name, {"facts": [{"label": "사례", "value": name}]}))
    common["actor_status"] = (
        "가상 주변 차량 정보"
        if common["actors"]
        else "이 사례에는 주변 차량 정보가 없습니다"
    )
    return common
