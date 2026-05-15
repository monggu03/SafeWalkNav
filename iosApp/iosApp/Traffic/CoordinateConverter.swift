//
//  CoordinateConverter.swift
//  iosApp
//
//  EPSG:5186 (Korea 2000 / Central Belt 2010, GRS80 Transverse Mercator)
//  → WGS84 (lat/lon) 변환.
//
//  proj4 파라미터:
//    +proj=tmerc +lat_0=38 +lon_0=127 +k=1
//    +x_0=200000 +y_0=600000 +ellps=GRS80 +units=m +no_defs
//
//  GRS80 과 WGS84 는 ellipsoid 파라미터가 사실상 동일하므로
//  (semi-major axis 동일, flattening 차이 ~10^-12)
//  datum shift 없이 inverse Transverse Mercator 만 수행해도
//  서브미터 수준 정확도가 나온다.
//

import Foundation

struct Wgs84Coordinate {
    let latitude: Double
    let longitude: Double
}

enum CoordinateConverter {

    // GRS80 ellipsoid
    private static let a: Double = 6_378_137.0
    private static let f: Double = 1.0 / 298.257222101
    private static let e2: Double = 2 * f - f * f          // first eccentricity squared
    private static let ep2: Double = e2 / (1 - e2)         // second eccentricity squared

    // EPSG:5186 projection parameters
    private static let lat0: Double = 38.0 * .pi / 180.0   // origin latitude (rad)
    private static let lon0: Double = 127.0 * .pi / 180.0  // central meridian (rad)
    private static let k0: Double = 1.0                    // scale factor
    private static let falseEasting: Double = 200_000.0
    private static let falseNorthing: Double = 600_000.0

    // Meridional arc length at the origin latitude (precomputed).
    private static let m0: Double = meridionalArc(lat0)

    /// EPSG:5186 (easting=x, northing=y, meters) → WGS84 (lat, lon in degrees).
    static func epsg5186ToWgs84(x: Double, y: Double) -> Wgs84Coordinate {

        let easting = x - falseEasting
        let northing = y - falseNorthing

        let m = m0 + northing / k0

        let mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256))

        let e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))

        // Footprint latitude (φ₁)
        let phi1 = mu
            + (3 * e1 / 2 - 27 * pow(e1, 3) / 32) * sin(2 * mu)
            + (21 * e1 * e1 / 16 - 55 * pow(e1, 4) / 32) * sin(4 * mu)
            + (151 * pow(e1, 3) / 96) * sin(6 * mu)
            + (1097 * pow(e1, 4) / 512) * sin(8 * mu)

        let sinPhi1 = sin(phi1)
        let cosPhi1 = cos(phi1)
        let tanPhi1 = tan(phi1)

        let n1 = a / sqrt(1 - e2 * sinPhi1 * sinPhi1)
        let t1 = tanPhi1 * tanPhi1
        let c1 = ep2 * cosPhi1 * cosPhi1
        let r1 = a * (1 - e2) / pow(1 - e2 * sinPhi1 * sinPhi1, 1.5)

        let d = easting / (n1 * k0)

        let lat = phi1 - (n1 * tanPhi1 / r1) * (
            d * d / 2
            - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * pow(d, 4) / 24
            + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * pow(d, 6) / 720
        )

        let lonOffset = (
            d
            - (1 + 2 * t1 + c1) * pow(d, 3) / 6
            + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * pow(d, 5) / 120
        ) / cosPhi1

        let lon = lon0 + lonOffset

        return Wgs84Coordinate(
            latitude: lat * 180.0 / .pi,
            longitude: lon * 180.0 / .pi
        )
    }

    /// 위도 φ (rad) 에서 적도까지의 자오선 호 길이 M(φ).
    private static func meridionalArc(_ phi: Double) -> Double {
        return a * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * pow(e2, 3) / 256) * phi
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * pow(e2, 3) / 1024) * sin(2 * phi)
            + (15 * e2 * e2 / 256 + 45 * pow(e2, 3) / 1024) * sin(4 * phi)
            - (35 * pow(e2, 3) / 3072) * sin(6 * phi)
        )
    }
}
