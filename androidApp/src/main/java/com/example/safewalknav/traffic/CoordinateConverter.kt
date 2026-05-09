package com.example.safewalknav.traffic

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

data class Wgs84Coordinate(
    val latitude: Double,
    val longitude: Double
)

object CoordinateConverter {

    private val crsFactory = CRSFactory()
    private val transformFactory = CoordinateTransformFactory()

    private val epsg5186 = crsFactory.createFromParameters(
        "EPSG:5186",
        "+proj=tmerc +lat_0=38 +lon_0=127 +k=1 " +
                "+x_0=200000 +y_0=600000 +ellps=GRS80 " +
                "+units=m +no_defs"
    )

    private val wgs84 = crsFactory.createFromParameters(
        "WGS84",
        "+proj=longlat +datum=WGS84 +no_defs"
    )

    private val transform = transformFactory.createTransform(epsg5186, wgs84)

    fun epsg5186ToWgs84(x: Double, y: Double): Wgs84Coordinate {
        val src = ProjCoordinate(x, y)
        val dst = ProjCoordinate()

        transform.transform(src, dst)

        return Wgs84Coordinate(
            latitude = dst.y,
            longitude = dst.x
        )
    }
}