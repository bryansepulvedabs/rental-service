-- =============================================
-- rental-service: initial data
-- =============================================
-- IDs de autos y usuarios deben existir en sus respectivos servicios.
-- car_id 10 (Yaris Cross) está availability=false porque tiene un arriendo PENDIENTE.
-- Los arriendos FINALIZADO y CANCELADO corresponden a autos disponibles (availability=true).

INSERT INTO rentals (car_id, user_id, start_date, end_date, status, total_price)
VALUES
    -- FINALIZADO: Carlos arrendó el Corolla (car 1) por 3 días
    (1, 1, '2026-08-01', '2026-08-04', 'FINALIZADO',  135000),

    -- CANCELADO: Valentina canceló el SUV Tucson (car 2) antes de iniciar
    (2, 2, '2026-08-10', '2026-08-15', 'CANCELADO',   325000),

    -- FINALIZADO: Andrés arrendó la Hilux (car 7) por 5 días
    (7, 3, '2026-08-20', '2026-08-25', 'FINALIZADO',  390000),

    -- PENDIENTE: Camila tiene activo el Yaris Cross híbrido (car 10) — availability=false
    (10, 4, '2026-09-10', '2026-09-15', 'PENDIENTE',  275000),

    -- FINALIZADO: Felipe arrendó el Picanto (car 5) por 2 días
    (5, 5, '2026-09-01', '2026-09-03', 'FINALIZADO',   56000);