# 天空渲染

星球维度从 `ship_flight` 数据包读取零海拔和大气参数，服务器在玩家登录和 `/reload` 时同步参数。M3 天体标识与维度标识一致，母星为 `minecraft:overworld`。本模组自带卫星维度 `blockspaceprogram:moon`，零海拔为 Y 63，表面重力为 1.6 m/s²。将维度的 `sea_level_density` 或 `atmosphere_top` 设为零即可使用真空天空。液体和状态效果仍使用原版雾。整合包可用同 ID 的数据包覆盖卫星地形。 [超平坦卫星示例](flight.md#覆盖卫星地形) · [卫星维度 #17](https://github.com/jxt159753/BlockSpaceProgram/issues/17)

相机经纬度按母星默认周长 377,088 格换算。渲染器从共享轨道时钟和 `OrbitalSystem.M3` 计算卫星视方向与视半径。M3 使用沿惯性系 `+Y` 的平行太阳光，母星每 1,200 轨道秒自转一圈，卫星朝向母星。天体球面按入射光显示相位，地面天空颜色和客户端光照按当地太阳高度变化。 [地表映射 #4](https://github.com/jxt159753/BlockSpaceProgram/issues/4) · [轨道数据层 #14](https://github.com/jxt159753/BlockSpaceProgram/issues/14)

飞到建筑高度上限以上后，可向下看到带昼夜分界的程序化星球球面。到达大气顶后，背景变黑、恒星可见，地形和天空的大气雾关闭。

## 轨道空间

进入轨道局部物理区后，天空使用参考轨道与相机在区内的偏移。星球随飞船在轨道上的位置改变。物理区重定基准时，服务端同步新的参考轨道。维度内的 `(x, y, z)` 偏移映射到惯性系的 `(x, -z, y)`，两边单位均为米。 [轨道局部物理区](flight.md#轨道局部物理区) · [局部物理区 #34](https://github.com/jxt159753/BlockSpaceProgram/issues/34)
